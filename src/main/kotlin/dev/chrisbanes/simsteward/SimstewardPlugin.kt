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
      val simslim = providers.gradleProperty("simsteward.simslim").orElse("simslim")
      val slim = providers.gradleProperty("simsteward.slim").map { it.toBoolean() }.orElse(true)

      val targets = extensions.getByType(KotlinMultiplatformExtension::class.java).targets
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
          providers.gradleProperty("simsteward.${platform.property}.deviceType"),
          providers.gradleProperty("simsteward.${platform.property}.runtime"),
          xcrun,
          simslim,
          slim,
          providers.gradleProperty("simsteward.${platform.property}.profile"),
        ))
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
  private val xcrun: Provider<String>,
  private val simslim: Provider<String>,
  private val slim: Provider<Boolean>,
  private val profile: Provider<String>,
) : Action<Task> {
  override fun execute(task: Task) {
    val service = leases.get()
    val slimming = Slimming(slim.get(), simslim.get(), profile.orNull)
    service.lease(task.path, name, service.resolve(platform, runtime.orNull, deviceType.orNull, xcrun.get(), slimming))
  }
}

internal const val NAME_PREFIX = "simsteward"

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
