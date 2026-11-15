package dev.chrisbanes.simsteward

import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.util.UUID
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
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

  private class ManagedSimulator(val udid: String, val poolName: String, val xcrun: String)

  private val created = mutableListOf<ManagedSimulator>()
  private val pools = mutableMapOf<SimulatorSpec, MutableList<ManagedSimulator>>()
  private val creating = mutableMapOf<SimulatorSpec, Int>()
  private val leases = mutableMapOf<String, ManagedSimulator>()
  private val lock = Object()

  /**
   * Leases a simulator from [spec]'s pool to [taskPath] and renames it to [name], growing the pool
   * up to [maxSimulators] or waiting for a returned lease.
   */
  internal fun lease(taskPath: String, name: String, spec: SimulatorSpec, maxSimulators: Int) {
    val simulator = reserve(taskPath, spec, maxSimulators) ?: create(spec).also { created ->
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
  private fun reserve(taskPath: String, spec: SimulatorSpec, maxSimulators: Int): ManagedSimulator? = synchronized(lock) {
    val pool = pools.getOrPut(spec) { mutableListOf() }
    fun idle() = pool.firstOrNull { it !in leases.values }
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

  private fun checkNameIsFree(simulator: ManagedSimulator, name: String) {
    @Suppress("UNCHECKED_CAST")
    val devices = (JsonSlurper().parseText(simctl(simulator.xcrun, "list", "devices", "-j")) as Map<String, Any?>)["devices"]
      as Map<String, List<Map<String, Any?>>>
    val other = devices.values.flatten().firstOrNull { it["name"] == name && it["udid"] != simulator.udid } ?: return
    throw GradleException(
      "Another simulator, ${other["udid"]}, is already named $name, so simctl could pick either one. " +
        "Delete or rename that simulator.",
    )
  }

  override fun onFinish(event: FinishEvent) {
    if (event !is TaskFinishEvent) return
    val taskPath = event.descriptor.taskPath
    val simulator = synchronized(lock) { leases[taskPath] } ?: return
    try {
      simctl(simulator.xcrun, "rename", simulator.udid, simulator.poolName)
    } finally {
      // Only now is it idle, so a waiting task cannot rename it before this rename back.
      synchronized(lock) {
        leases.remove(taskPath)
        lock.notifyAll()
      }
    }
  }

  override fun close(): Unit = synchronized(lock) {
    val failures = created.mapNotNull { runCatching { simctl(it.xcrun, "delete", it.udid) }.exceptionOrNull() }
    created.clear()
    pools.clear()
    creating.clear()
    leases.clear()
    failures.firstOrNull()?.let { first -> throw first.apply { failures.drop(1).forEach(::addSuppressed) } }
  }

  /** Runs outside the lock so that pools create simulators concurrently. */
  private fun create(spec: SimulatorSpec): ManagedSimulator = try {
    val poolName = "$NAME_PREFIX-${UUID.randomUUID().toString().take(8)}"
    val udid = simctl(spec.xcrun, "create", poolName, spec.deviceType, spec.runtime)
    val simulator = ManagedSimulator(udid, poolName, spec.xcrun)
    synchronized(lock) { created += simulator }
    // Stock simulators must be booted: KGP needs a booted device when standalone is false.
    simctl(spec.xcrun, "bootstatus", udid, "-b")
    simulator
  } catch (failure: Throwable) {
    synchronized(lock) {
      creating.merge(spec, -1, Int::plus)
      lock.notifyAll()
    }
    throw failure
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
    val logger: Logger = Logging.getLogger(LeaseService::class.java)
  }
}
