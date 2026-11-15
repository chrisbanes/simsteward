package dev.chrisbanes.simsteward

import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.util.UUID
import javax.inject.Inject
import org.gradle.api.GradleException
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
  private val leases = mutableMapOf<String, ManagedSimulator>()
  private val lock = Object()

  /** Leases a simulator from [spec]'s pool to [taskPath] and renames it to [name]. */
  internal fun lease(taskPath: String, name: String, spec: SimulatorSpec) = synchronized(lock) {
    val pool = pools.getOrPut(spec) { mutableListOf() }
    var simulator = pool.firstOrNull { it !in leases.values }
    while (simulator == null) {
      if (pool.size < MAX_SIMULATORS_PER_POOL) {
        // shortcut: creation holds the lock, so other pools wait for it; fine with one pool.
        simulator = create(spec).also { pool += it }
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
    val failures = created.mapNotNull { runCatching { simctl(it.xcrun, "delete", it.udid) }.exceptionOrNull() }
    created.clear()
    pools.clear()
    leases.clear()
    failures.firstOrNull()?.let { first -> throw first.apply { failures.drop(1).forEach(::addSuppressed) } }
  }

  private fun create(spec: SimulatorSpec): ManagedSimulator {
    val poolName = "$NAME_PREFIX-${UUID.randomUUID().toString().take(8)}"
    val udid = simctl(spec.xcrun, "create", poolName, spec.deviceType, spec.runtime)
    val simulator = ManagedSimulator(udid, poolName, spec.xcrun)
    created += simulator
    // Stock simulators must be booted: KGP needs a booted device when standalone is false.
    simctl(spec.xcrun, "bootstatus", udid, "-b")
    return simulator
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
    // shortcut: one simulator per pool until the cap becomes configurable.
    const val MAX_SIMULATORS_PER_POOL = 1
    const val STDERR_TAIL_LINES = 20
  }
}
