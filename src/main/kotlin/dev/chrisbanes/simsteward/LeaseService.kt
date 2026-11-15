// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
  val slimming: Slimming,
) : Serializable

/** Where this build's ownership records live (under the Gradle user home) and which build owns them. */
internal data class Ownership(val recordsDir: File, val buildRoot: String) : Serializable

/** The slimming half of a [SimulatorSpec]. */
internal data class Slimming(val slim: Boolean, val simslim: String, val profile: String?) : Serializable

/**
 * Holds every pool of managed simulators in the build.
 *
 * It has no parameters because projects register it in no fixed order (ADR 0002): each lease
 * request carries its own [SimulatorSpec].
 */
internal abstract class LeaseService :
  BuildService<BuildServiceParameters.None>,
  OperationCompletionListener,
  AutoCloseable {

  @get:Inject
  internal abstract val exec: ExecOperations

  private data class SpecKey(val platform: Platform, val runtime: String?, val deviceType: String?, val xcrun: String)

  private class ManagedSimulator(val udid: String, val poolName: String, val xcrun: String, val record: File)

  private var reaped = false
  private val specs = ConcurrentHashMap<SpecKey, SimulatorSpec>()

  private val created = mutableListOf<ManagedSimulator>()
  private val pools = mutableMapOf<SimulatorSpec, MutableList<ManagedSimulator>>()
  private val creating = mutableMapOf<SimulatorSpec, Int>()
  private val leases = mutableMapOf<String, ManagedSimulator>()
  private val returning = mutableSetOf<ManagedSimulator>()
  private val lock = Object()

  /** Resolves the spec for [platform]; only asks `simctl` when something is not pinned. */
  internal fun resolve(platform: Platform, runtime: String?, deviceType: String?, xcrun: String, slimming: Slimming): SimulatorSpec =
    // Slimming is not part of the key, so projects that differ only in it still share one `simctl list`.
    specs.computeIfAbsent(SpecKey(platform, runtime, deviceType, xcrun)) {
      resolveSpec(platform, { simctl(xcrun, "list", "-j", "runtimes") }, runtime, deviceType, xcrun, slimming)
    }.copy(slimming = slimming)

  /**
   * Leases a simulator from [spec]'s pool to [taskPath] and renames it to [name], growing the pool
   * up to [maxSimulators] or waiting for a returned lease.
   */
  internal fun lease(taskPath: String, name: String, spec: SimulatorSpec, ownership: Ownership, maxSimulators: Int) {
    val simulator = reserve(taskPath, spec, ownership, maxSimulators) ?: create(spec, ownership).also { created ->
      synchronized(lock) {
        creating.merge(spec, -1, Int::plus)
        pools.getValue(spec) += created
        leases[taskPath] = created
      }
    }
    // The lease is held from here on, so onFinish returns it even if the checks below fail.
    checkNameIsFree(simulator, name)
    simctl(simulator.xcrun, "rename", simulator.udid, name)
    logger.lifecycle("simsteward: $taskPath leased managed simulator ${simulator.udid} (${simulator.poolName}) as $name")
  }

  /** Leases an idle simulator to [taskPath], or returns null once the caller may create one. */
  private fun reserve(taskPath: String, spec: SimulatorSpec, ownership: Ownership, maxSimulators: Int): ManagedSimulator? =
    synchronized(lock) {
      if (!reaped) {
        // shortcut: reaps once, with the first lease's xcrun and records dir; reap per distinct pair if builds mix them.
        reap(spec.xcrun, ownership.recordsDir)
        reaped = true
      }
      val pool = pools.getOrPut(spec) { mutableListOf() }
      fun idle() = pool.firstOrNull { it !in leases.values && it !in returning }
      var waiting = false
      while (idle() == null && pool.size + creating.getOrDefault(spec, 0) >= maxSimulators) {
        if (!waiting) {
          waiting = true
          val simulators = if (maxSimulators == 1) "simulator" else "simulators"
          logger.lifecycle("simsteward: $taskPath is waiting for a returned lease: its pool is at its cap of $maxSimulators $simulators")
        }
        lock.wait()
      }
      idle()?.also { leases[taskPath] = it } ?: run {
        creating.merge(spec, 1, Int::plus)
        null
      }
    }

  /** `simctl` silently picks one of several same-named simulators (ADR 0001). */
  private fun checkNameIsFree(simulator: ManagedSimulator, name: String) {
    val (_, otherUdid) = listDevices(simulator.xcrun).firstOrNull { (n, udid) -> n == name && udid != simulator.udid } ?: return
    throw GradleException(
      "Another simulator, $otherUdid, is already named $name, so simctl could pick either one. " +
        "Delete or rename that simulator.",
    )
  }

  /**
   * Returns [taskPath]'s lease, if it holds one. The task calls this when its own work ends;
   * [onFinish] calls it again as the backstop, and only the first call does anything.
   */
  internal fun release(taskPath: String) {
    val simulator = synchronized(lock) { leases.remove(taskPath)?.also { returning += it } } ?: return
    try {
      simctl(simulator.xcrun, "rename", simulator.udid, simulator.poolName)
    } finally {
      // Only now is it idle, so a waiting task cannot rename it before this rename back.
      synchronized(lock) {
        returning -= simulator
        lock.notifyAll()
      }
    }
  }

  /** Gradle delivers these off-thread and late, so tasks normally return their lease first. */
  override fun onFinish(event: FinishEvent) {
    if (event is TaskFinishEvent) release(event.descriptor.taskPath)
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
    creating.clear()
    leases.clear()
    returning.clear()
    failures.firstOrNull()?.let { first -> throw first.apply { failures.drop(1).forEach(::addSuppressed) } }
  }

  /** Runs outside the lock so that pools create simulators concurrently. */
  private fun create(spec: SimulatorSpec, ownership: Ownership): ManagedSimulator = try {
    val poolName = "$NAME_PREFIX-${UUID.randomUUID().toString().take(8)}"
    // The record is written first so that an interruption inside `create` leaves a trace by name.
    val record = ownership.recordsDir.resolve("$poolName.properties")
    val owner = ProcessHandle.current()
    val values = mapOf(
      "name" to poolName,
      "buildRoot" to ownership.buildRoot,
      "pid" to owner.pid().toString(),
      "startTime" to owner.info().startInstant().get().toEpochMilli().toString(),
    )
    writeRecord(record, values)
    // A failed create leaves the record behind: it may still have made the simulator.
    val udid = simctl(spec.xcrun, "create", poolName, spec.deviceType, spec.runtime)
    writeRecord(record, values + ("udid" to udid))
    val simulator = ManagedSimulator(udid, poolName, spec.xcrun, record)
    synchronized(lock) { created += simulator }
    val slimming = spec.slimming
    if (slimming.slim) {
      // `simslim on` leaves the simulator booted; there is no fallback to stock if it fails.
      val profile = listOfNotNull(slimming.profile?.let { "--profile" }, slimming.profile)
      run(listOf(slimming.simslim, "on", udid) + profile)
      run(listOf(slimming.simslim, "verify", udid) + profile)
    } else {
      // Stock simulators must be booted: KGP needs a booted device when standalone is false.
      simctl(spec.xcrun, "bootstatus", udid, "-b")
    }
    simulator
  } catch (failure: Throwable) {
    synchronized(lock) {
      creating.merge(spec, -1, Int::plus)
      lock.notifyAll()
    }
    throw failure
  }

  /**
   * Deletes the simulator of every record whose owner is gone, and reports simulators that carry
   * the simsteward prefix but have no record. Only a record's UDID, or its name when the UDID was
   * never known, selects a simulator to delete.
   */
  internal fun reap(xcrun: String, recordsDir: File) = synchronized(lock) {
    val devices = listDevices(xcrun)
    val records = recordsDir.listFiles { f -> f.extension == "properties" }.orEmpty().mapNotNull { file ->
      val record = readRecord(file)
      if (record["name"] == null || record["pid"]?.toLongOrNull() == null || record["startTime"]?.toLongOrNull() == null) {
        logger.warn("Ignoring unreadable simsteward record $file")
        null
      } else {
        file to record
      }
    }
    records.filterNot { (_, record) -> ownerIsAlive(record.getValue("pid").toLong(), record.getValue("startTime").toLong()) }
      .forEach { (file, record) ->
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

  /** The `name to udid` of every simulator `simctl list devices` reports. */
  private fun listDevices(xcrun: String): List<Pair<String, String>> =
    simctl(xcrun, "list", "devices").lines().mapNotNull { DEVICE_LINE.matchEntire(it)?.destructured?.let { (name, udid) -> name to udid } }

  private fun ownerIsAlive(pid: Long, startTime: Long): Boolean = ProcessHandle.of(pid).map { handle ->
    // shortcut: an owner whose start time is unknown counts as alive, so its simulators leak until a later
    // build sees the PID gone; compare another process attribute if that leaks in practice.
    handle.info().startInstant().map { it.toEpochMilli() == startTime }.orElse(true)
  }.orElse(false)

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
    const val STDERR_TAIL_LINES = 20
    val DEVICE_LINE = Regex("""^\s+(.+) \(([0-9A-Fa-f-]{36})\) \(.*""")
    val logger = Logging.getLogger(LeaseService::class.java)
  }
}
