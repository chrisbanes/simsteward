package dev.chrisbanes.simsteward

import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.build.event.BuildEventsListenerRegistry
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

abstract class SimstewardPlugin @Inject constructor(
  private val buildEvents: BuildEventsListenerRegistry,
) : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
      val leases = gradle.sharedServices.registerIfAbsent("simsteward", LeaseService::class.java) {}
      buildEvents.onTaskCompletion(leases)

      val buildRootHash = sha256(layout.settingsDirectory.asFile.absolutePath).take(8)
      val xcrun = providers.gradleProperty("simsteward.xcrun").orElse("/usr/bin/xcrun")

      val extension = extensions.create("simsteward", SimstewardExtension::class.java)
      extension.enabled.convention(providers.gradleProperty("simsteward.enabled").map { it.toBoolean() }.orElse(true))
      extension.slim.convention(providers.gradleProperty("simsteward.slim").map { it.toBoolean() }.orElse(true))
      extension.maxSimulators.convention(providers.gradleProperty("simsteward.maxSimulators").map { it.toInt() }.orElse(1))
      Platform.entries.forEach { platform ->
        val settings = extension.of(platform)
        listOf("deviceType" to settings.deviceType, "runtime" to settings.runtime, "profile" to settings.profile)
          .forEach { (name, property) -> property.convention(providers.gradleProperty("simsteward.${platform.property}.$name")) }
      }

      val targets = extensions.getByType(KotlinMultiplatformExtension::class.java).targets
      // Deferred so that the project's own `simsteward {}` block has run.
      afterEvaluate {
      if (!extension.enabled.get()) return@afterEvaluate
      tasks.withType(KotlinNativeSimulatorTest::class.java).configureEach { task ->
        val kotlinTarget = targets.getByName(checkNotNull(task.targetName)) as KotlinNativeTarget
        val platform = Platform.of(kotlinTarget.konanTarget.family) ?: return@configureEach
        val name = "$NAME_PREFIX-$buildRootHash${task.path}"
        task.standalone.set(false)
        task.device.set(name)
        task.usesService(leases)
        task.doFirst(LeaseAction(
          leases,
          name,
          platform,
          extension.of(platform).deviceType,
          extension.of(platform).runtime,
          extension.of(platform).profile,
          extension.slim,
          extension.maxSimulators,
          xcrun,
        ))
      }
      }
    }
  }
}

/** Takes the task's lease when it actually runs; [LeaseService.onFinish] returns it. */
private class LeaseAction(
  private val leases: Provider<LeaseService>,
  private val name: String,
  private val platform: Platform,
  private val deviceType: Provider<String>,
  private val runtime: Provider<String>,
  private val profile: Provider<String>,
  private val slim: Provider<Boolean>,
  private val maxSimulators: Provider<Int>,
  private val xcrun: Provider<String>,
) : Action<Task> {
  override fun execute(task: Task) {
    val service = leases.get()
    service.lease(task.path, name, service.resolve(platform, runtime.orNull, deviceType.orNull, xcrun.get())
      .copy(slim = slim.get(), profile = profile.orNull, maxSimulators = maxSimulators.get()))
  }
}

internal const val NAME_PREFIX = "simsteward"

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
