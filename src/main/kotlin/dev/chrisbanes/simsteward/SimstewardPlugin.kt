package dev.chrisbanes.simsteward

import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.build.event.BuildEventsListenerRegistry
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.konan.target.Family

abstract class SimstewardPlugin @Inject constructor(
  private val buildEvents: BuildEventsListenerRegistry,
) : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
      val leases = gradle.sharedServices.registerIfAbsent("simsteward", LeaseService::class.java) {}
      buildEvents.onTaskCompletion(leases)

      val buildRootHash = sha256(layout.settingsDirectory.asFile.absolutePath).take(8)
      val deviceType = providers.gradleProperty("simsteward.ios.deviceType")
      val runtime = providers.gradleProperty("simsteward.ios.runtime")
      val xcrun = providers.gradleProperty("simsteward.xcrun").orElse("/usr/bin/xcrun")

      val targets = extensions.getByType(KotlinMultiplatformExtension::class.java).targets
      tasks.withType(KotlinNativeSimulatorTest::class.java).configureEach { task ->
        val kotlinTarget = targets.getByName(checkNotNull(task.targetName)) as KotlinNativeTarget
        // shortcut: iOS only until tvOS and watchOS get their own specs.
        if (kotlinTarget.konanTarget.family != Family.IOS) return@configureEach
        val name = "$NAME_PREFIX-$buildRootHash${task.path}"
        task.standalone.set(false)
        task.device.set(name)
        task.usesService(leases)
        task.doFirst(LeaseAction(leases, name, deviceType, runtime, xcrun))
      }
    }
  }
}

/** Takes the task's lease when it actually runs; [LeaseService.onFinish] returns it. */
private class LeaseAction(
  private val leases: Provider<LeaseService>,
  private val name: String,
  private val deviceType: Provider<String>,
  private val runtime: Provider<String>,
  private val xcrun: Provider<String>,
) : Action<Task> {
  override fun execute(task: Task) {
    val spec = SimulatorSpec(
      deviceType = deviceType.orNull ?: throw missingProperty("simsteward.ios.deviceType", "device type"),
      runtime = runtime.orNull ?: throw missingProperty("simsteward.ios.runtime", "runtime"),
      xcrun = xcrun.get(),
    )
    leases.get().lease(task.path, name, spec)
  }

  private fun missingProperty(property: String, what: String) =
    GradleException("Set the Gradle property $property to an installed iOS simulator $what identifier.")
}

internal const val NAME_PREFIX = "simsteward"

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
