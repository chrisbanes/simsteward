package dev.chrisbanes.simsteward

import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
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
      val xcrun = providers.gradleProperty("simsteward.xcrun").orElse("/usr/bin/xcrun")
      val simslim = providers.gradleProperty("simsteward.simslim").orElse("simslim")
      val slim = providers.gradleProperty("simsteward.slim").map { it.toBoolean() }.orElse(true)

      // KGP sets a test task's targetName after configureEach callbacks run, so the platform is looked up at execution.
      val families = objects.mapProperty(String::class.java, Family::class.java)
      extensions.getByType(KotlinMultiplatformExtension::class.java).targets.withType(KotlinNativeTarget::class.java)
        .configureEach { families.put(it.name, it.konanTarget.family) }
      tasks.withType(KotlinNativeSimulatorTest::class.java).configureEach { task ->
        val name = "$NAME_PREFIX-$buildRootHash${task.path}"
        task.standalone.set(false)
        task.device.set(name)
        task.usesService(leases)
        task.doFirst(LeaseAction(
          leases,
          name,
          families,
          providers,
          xcrun,
          simslim,
          slim,
        ))
      }
    }
  }
}

/** Takes the task's lease when it actually runs; [LeaseService.onFinish] returns it. */
private class LeaseAction(
  private val leases: Provider<LeaseService>,
  private val name: String,
  private val families: Provider<Map<String, Family>>,
  private val providers: ProviderFactory,
  private val xcrun: Provider<String>,
  private val simslim: Provider<String>,
  private val slim: Provider<Boolean>,
) : Action<Task> {
  override fun execute(task: Task) {
    val family = families.get().getValue(checkNotNull((task as KotlinNativeSimulatorTest).targetName))
    val platform = Platform.of(family) ?: return
    val service = leases.get()
    fun property(key: String) = providers.gradleProperty("simsteward.${platform.property}.$key").orNull
    val slimming = Slimming(slim.get(), simslim.get(), property("profile"))
    service.lease(task.path, name, service.resolve(platform, property("runtime"), property("deviceType"), xcrun.get(), slimming))
  }
}

internal const val NAME_PREFIX = "simsteward"

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
