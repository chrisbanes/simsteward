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
import org.gradle.tooling.events.task.TaskSuccessResult

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

  private val reaped = mutableSetOf<Pair<String, File>>()
  private val ownerIdentity by lazy {
    val owner = ProcessHandle.current()
    val start = owner.info().startInstant().get().toEpochMilli()
    mapOf("pid" to owner.pid().toString(), "startTime" to start.toString()) +
      listOfNotNull(readOwnerStartSeconds(exec, owner.pid())?.takeIf { it == start / 1000 }?.let { "ownerStartTimeSeconds" to it.toString() }).toMap()
  }
  private val specs = ConcurrentHashMap<SpecKey, Result<SimulatorSpec>>()
  private val pending = mutableMapOf<String, Pair<File, Map<String, String>>>()

  private val created = mutableListOf<ManagedSimulator>()
  private val pools = mutableMapOf<SimulatorSpec, MutableList<ManagedSimulator>>()
  private val creating = mutableMapOf<SimulatorSpec, Int>()
  private val leases = mutableMapOf<String, ManagedSimulator>()
  private val returning = mutableSetOf<ManagedSimulator>()
  private val waitingByTask = mutableMapOf<String, SimulatorSpec>()

  // wait/notifyAll live on java.lang.Object, not kotlin.Any.
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private val lock = Object()

  /** Resolves the spec for [platform]; only asks `simctl` when something is not pinned. */
  internal fun resolve(platform: Platform, runtime: String?, deviceType: String?, xcrun: String, slimming: Slimming): SimulatorSpec =
    // Slimming is not part of the key, so projects that differ only in it still share one `simctl list`.
    specs.computeIfAbsent(SpecKey(platform, runtime, deviceType, xcrun)) {
      runCatching { resolveSpec(platform, { simctl(xcrun, "list", "-j", "runtimes") }, runtime, deviceType, xcrun, slimming) }
    }.getOrThrow().copy(slimming = slimming)

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
    // Creation/boot and other pools' creates or returning renames run outside the lock after reap.
    // Its listing cannot establish that this task name is still free.
    checkNameIsFree(simulator, name)
    simctl(simulator.xcrun, "rename", simulator.udid, name)
    logger.lifecycle("simsteward: $taskPath leased managed simulator ${simulator.udid} (${simulator.poolName}) as $name")
  }

  /** Leases an idle simulator to [taskPath], or returns null once the caller may create one. */
  private fun reserve(taskPath: String, spec: SimulatorSpec, ownership: Ownership, maxSimulators: Int): ManagedSimulator? =
    synchronized(lock) {
      val key = spec.xcrun to ownership.recordsDir.absoluteFile.normalize()
      if (key !in reaped) {
        reap(spec.xcrun, ownership.recordsDir)
        reaped += key
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
        waitingByTask[taskPath] = spec
        try {
          lock.wait()
        } finally {
          waitingByTask.remove(taskPath)
        }
      }
      idle()?.also { leases[taskPath] = it } ?: run {
        creating.merge(spec, 1, Int::plus)
        null
      }
    }

  fun isWaitingForLease(taskPaths: Set<String>): Boolean = synchronized(lock) {
    taskPaths.any { it in waitingByTask }
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

  internal fun stage(taskPath: String, file: File, spec: SimulatorSpec) {
    synchronized(lock) { pending[taskPath] = file to spec.identifiers() }
  }

  /** Gradle delivers these off-thread and late, so tasks normally return their lease first. */
  override fun onFinish(event: FinishEvent) {
    if (event is TaskFinishEvent) {
      val path = event.descriptor.taskPath
      val state = synchronized(lock) { pending.remove(path) }
      try {
        val result = event.result
        if (state != null && result is TaskSuccessResult && !result.isUpToDate && !result.isFromCache) {
          runCatching { writeRecord(state.first, state.second) }
            .onFailure { logger.warn("Could not save resolved identifiers for $path at ${state.first}: ${it.message}") }
        }
      } finally {
        release(path)
      }
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
    creating.clear()
    leases.clear()
    returning.clear()
    waitingByTask.clear()
    reaped.clear()
    failures.firstOrNull()?.let { first -> throw first.apply { failures.drop(1).forEach(::addSuppressed) } }
  }

  /** Runs outside the lock so that pools create simulators concurrently. */
  private fun create(spec: SimulatorSpec, ownership: Ownership): ManagedSimulator = try {
    val poolName = "$NAME_PREFIX-${UUID.randomUUID().toString().take(8)}"
    // The record is written first so that an interruption inside `create` leaves a trace by name.
    val record = ownership.recordsDir.resolve("$poolName.properties")
    val values = ownerIdentity + mapOf(
      "name" to poolName,
      "buildRoot" to ownership.buildRoot,
      "xcrun" to spec.xcrun,
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
  internal fun reap(
    xcrun: String,
    recordsDir: File,
    observe: (Long, Long?) -> OwnerProcess = ::observeOwner,
  ) = synchronized(lock) {
    val devices = listDevices(xcrun)
    val records = recordsDir.listFiles { f -> f.extension == "properties" }.orEmpty().mapNotNull { file ->
      val record = readRecord(file)
      if (record["name"] == null || record["pid"]?.toLongOrNull()?.takeIf { it > 0 } == null || record["startTime"]?.toLongOrNull()?.takeIf { it > 0 } == null) {
        logger.warn("Ignoring unreadable simsteward record $file")
        null
      } else {
        file to record
      }
    }
    records.filter { (_, record) -> record["xcrun"] == null || record["xcrun"] == xcrun }
      .filterNot { (file, record) ->
        val pid = record.getValue("pid").toLong()
        val start = record.getValue("startTime").toLong()
        val seconds = record["ownerStartTimeSeconds"]?.toLongOrNull()?.takeIf { it > 0 && it == start / 1000 }
        val alive = ownerIsAlive(start, seconds) { observe(pid, seconds) }
        if (alive == null) logger.warn("Cannot establish owner identity for simsteward record $file; leaving it alone.")
        alive != false
      }
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

  private fun observeOwner(pid: Long, seconds: Long?): OwnerProcess {
    val handle = ProcessHandle.of(pid).orElse(null)
    if (handle == null || !handle.isAlive) return OwnerProcess(false, null, null)
    val javaStart = handle.info().startInstant().orElse(null)?.toEpochMilli()
    return OwnerProcess(true, javaStart, if (javaStart == null && seconds != null) readOwnerStartSeconds(exec, pid) else null)
  }

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

internal fun SimulatorSpec.identifiers(): Map<String, String> = mapOf("runtime" to runtime, "deviceType" to deviceType)
