package dev.chrisbanes.simsteward

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable
import java.util.Properties
import java.util.UUID
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.process.ExecOperations
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent

/** Everything that makes two managed simulators interchangeable. */
internal data class SimulatorSpec(
  val deviceType: String,
  val runtime: String,
  val xcrun: String,
) : Serializable

/** Where this build's ownership records live (under the Gradle user home) and which build owns them. */
internal data class Ownership(val recordsDir: File, val buildRoot: String) : Serializable

/**
 * Holds every pool of managed simulators in the build.
 *
 * It has no parameters because projects register it in no fixed order (ADR 0002): each lease
 * request carries its own [SimulatorSpec].
 */
abstract class LeaseService :
  BuildService<BuildServiceParameters.None>,
  OperationCompletionListener,
  AutoCloseable {

  @get:Inject
  internal abstract val exec: ExecOperations

  private class ManagedSimulator(val udid: String, val poolName: String, val xcrun: String, val record: File)

  private var reaped = false

  private val created = mutableListOf<ManagedSimulator>()
  private val pools = mutableMapOf<SimulatorSpec, MutableList<ManagedSimulator>>()
  private val leases = mutableMapOf<String, ManagedSimulator>()
  private val lock = Object()

  /** Resolves the spec for [platform]; only asks `simctl` when something is not pinned. */
  internal fun resolve(platform: Platform, runtime: String?, deviceType: String?, xcrun: String): SimulatorSpec =
    if (runtime != null && deviceType != null) {
      SimulatorSpec(deviceType, runtime, xcrun)
    } else {
      resolveSpec(platform, simctl(xcrun, "list", "-j", "runtimes"), runtime, deviceType, xcrun)
    }

  /** Leases a simulator from [spec]'s pool to [taskPath] and renames it to [name]. */
  internal fun lease(taskPath: String, name: String, spec: SimulatorSpec, ownership: Ownership) = synchronized(lock) {
    if (!reaped) {
      reap(spec.xcrun, ownership.recordsDir)
      reaped = true
    }
    val pool = pools.getOrPut(spec) { mutableListOf() }
    var simulator = pool.firstOrNull { it !in leases.values }
    while (simulator == null) {
      if (pool.size < MAX_SIMULATORS_PER_POOL) {
        // shortcut: creation holds the lock, so other pools wait for it; fine with one pool.
        simulator = create(spec, ownership).also { pool += it }
      } else {
        lock.wait()
        simulator = pool.firstOrNull { it !in leases.values }
      }
    }
    simctl(simulator.xcrun, "rename", simulator.udid, name)
    leases[taskPath] = simulator
  }

  override fun onFinish(event: FinishEvent) {
    if (event !is TaskFinishEvent) return
    synchronized(lock) {
      val simulator = leases.remove(event.descriptor.taskPath) ?: return
      lock.notifyAll()
      simctl(simulator.xcrun, "rename", simulator.udid, simulator.poolName)
    }
  }

  override fun close(): Unit = synchronized(lock) {
    val failures = created.mapNotNull {
      runCatching {
        simctl(it.xcrun, "delete", it.udid)
        // Kept when deletion fails, so a later build can reap it.
        it.record.delete()
      }.exceptionOrNull()
    }
    created.clear()
    pools.clear()
    leases.clear()
    failures.firstOrNull()?.let { first -> throw first.apply { failures.drop(1).forEach(::addSuppressed) } }
  }

  private fun create(spec: SimulatorSpec, ownership: Ownership): ManagedSimulator {
    val poolName = "$NAME_PREFIX-${UUID.randomUUID().toString().take(8)}"
    // The record is written first so that an interruption inside `create` leaves a trace by name.
    val record = ownership.recordsDir.resolve("$poolName.properties")
    val owner = ProcessHandle.current()
    writeRecord(record, mapOf(
      "name" to poolName,
      "buildRoot" to ownership.buildRoot,
      "pid" to owner.pid().toString(),
      "startTime" to owner.info().startInstant().get().toEpochMilli().toString(),
    ))
    // A failed create leaves the record behind: it may still have made the simulator.
    val udid = simctl(spec.xcrun, "create", poolName, spec.deviceType, spec.runtime)
    writeRecord(record, readRecord(record) + ("udid" to udid))
    val simulator = ManagedSimulator(udid, poolName, spec.xcrun, record)
    created += simulator
    // Stock simulators must be booted: KGP needs a booted device when standalone is false.
    simctl(spec.xcrun, "bootstatus", udid, "-b")
    return simulator
  }

  /**
   * Deletes the simulator of every record whose owner is gone, and reports simulators that carry
   * the simsteward prefix but have no record. Only a record's UDID, or its name when the UDID was
   * never known, selects a simulator to delete.
   */
  internal fun reap(xcrun: String, recordsDir: File) = synchronized(lock) {
    val devices = simctl(xcrun, "list", "devices").lines().mapNotNull { DEVICE_LINE.matchEntire(it)?.destructured }
      .map { (name, udid) -> name to udid }
    val records = recordsDir.listFiles { f -> f.extension == "properties" }.orEmpty().mapNotNull { file ->
      val record = readRecord(file)
      val name = record["name"]
      val pid = record["pid"]?.toLongOrNull()
      val start = record["startTime"]?.toLongOrNull()
      if (name == null || pid == null || start == null) {
        logger.warn("Ignoring unreadable simsteward record $file")
        null
      } else {
        Triple(file, record, ownerIsAlive(pid, start))
      }
    }
    records.filter { !it.third }.forEach { (file, record, _) ->
      val udid = record["udid"]
      val targets = devices.filter { (name, id) -> if (udid != null) id == udid else name == record["name"] }
      val failed = targets.any { (name, id) ->
        runCatching { simctl(xcrun, "delete", id) }.onFailure { logger.warn("Could not reap simulator $name ($id): ${it.message}") }.isFailure
      }
      if (!failed) file.delete()
    }
    val recordedNames = records.mapNotNull { it.second["name"] }.toSet()
    val recordedUdids = records.mapNotNull { it.second["udid"] }.toSet()
    devices.filter { (name, id) -> name.startsWith("$NAME_PREFIX-") && name !in recordedNames && id !in recordedUdids }
      .forEach { (name, id) -> logger.warn("Found simulator $name ($id) with the simsteward prefix but no ownership record; leaving it alone.") }
  }

  private fun ownerIsAlive(pid: Long, startTime: Long): Boolean = ProcessHandle.of(pid).map { handle ->
    // Without a start time we cannot prove the PID was reused, so the owner counts as alive.
    handle.info().startInstant().map { it.toEpochMilli() == startTime }.orElse(true)
  }.orElse(false)

  private fun writeRecord(file: File, values: Map<String, String>) {
    file.parentFile.mkdirs()
    val properties = Properties().apply { values.forEach { (k, v) -> setProperty(k, v) } }
    file.writer().use { properties.store(it, null) }
  }

  private fun readRecord(file: File): Map<String, String> = runCatching {
    Properties().apply { file.reader().use(::load) }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
  }.getOrDefault(emptyMap())

  private fun simctl(xcrun: String, vararg arguments: String): String = run(listOf(xcrun, "simctl", *arguments))

  private fun run(command: List<String>): String {
    val output = ByteArrayOutputStream()
    val errors = ByteArrayOutputStream()
    val result = exec.exec {
      it.commandLine(command)
      it.standardOutput = output
      it.errorOutput = errors
      it.isIgnoreExitValue = true
    }
    if (result.exitValue != 0) {
      val stderr = errors.toString(Charsets.UTF_8).lines().takeLast(STDERR_TAIL_LINES).joinToString("\n").trim()
      throw GradleException("Simulator command failed with exit code ${result.exitValue}: ${command.joinToString(" ")}\n$stderr")
    }
    return output.toString(Charsets.UTF_8).trim()
  }

  private companion object {
    // shortcut: one simulator per pool until the cap becomes configurable.
    const val MAX_SIMULATORS_PER_POOL = 1
    const val STDERR_TAIL_LINES = 20
    val DEVICE_LINE = Regex("""^\s+(.+) \(([0-9A-Fa-f-]{36})\) \(.*""")
    val logger = Logging.getLogger(LeaseService::class.java)
  }
}
