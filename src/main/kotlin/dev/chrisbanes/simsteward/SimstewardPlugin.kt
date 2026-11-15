package dev.chrisbanes.simsteward

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
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

      val buildRoot = layout.settingsDirectory.asFile.absolutePath
      val buildRootHash = sha256(buildRoot).take(8)
      val xcrun = providers.gradleProperty("simsteward.xcrun").orElse("/usr/bin/xcrun")
      val simslim = providers.gradleProperty("simsteward.simslim").orElse("simslim")

      val extension = extensions.create("simsteward", SimstewardExtension::class.java)
      extension.enabled.convention(providers.gradleProperty("simsteward.enabled").map { it.toBoolean() }.orElse(true))
      extension.slim.convention(providers.gradleProperty("simsteward.slim").map { it.toBoolean() }.orElse(true))
      extension.maxSimulators.convention(providers.gradleProperty("simsteward.maxSimulators").map(::parseMaxSimulators).orElse(2))
      Platform.entries.forEach { platform ->
        val settings = extension.of(platform)
        listOf("deviceType" to settings.deviceType, "runtime" to settings.runtime)
          .forEach { (name, property) -> property.convention(providers.gradleProperty("simsteward.${platform.property}.$name")) }
        // A build-wide property is relative to the build root; a project's own value, to that project.
        settings.profile.convention(providers.gradleProperty("simsteward.${platform.property}.profile").map { File(buildRoot).resolve(it).path })
      }

      val recordsDir = gradle.gradleUserHomeDir.resolve("simsteward/records")
      tasks.register("simstewardReap", ReapTask::class.java) {
        it.group = "verification"
        it.description = "Deletes managed simulators whose owning build is gone."
        it.service.set(leases)
        it.usesService(leases)
        it.xcrun.set(xcrun)
        it.recordsDir.set(layout.dir(providers.provider { recordsDir }))
      }

      val targets = extensions.getByType(KotlinMultiplatformExtension::class.java).targets
      // KGP sets a test task's targetName after configureEach callbacks run, so the platform is looked up at execution.
      val families = objects.mapProperty(String::class.java, Family::class.java)
      targets.withType(KotlinNativeTarget::class.java).configureEach { families.put(it.name, it.konanTarget.family) }
      // Deferred so that the project's own `simsteward {}` block has run.
      afterEvaluate {
        if (!extension.enabled.get()) return@afterEvaluate
        tasks.withType(KotlinNativeSimulatorTest::class.java).configureEach { task ->
          val name = "$NAME_PREFIX-$buildRootHash${task.path}"
          task.standalone.set(false)
          task.device.set(name)
          task.usesService(leases)
          // Inputs reflect only what is pinned; unpinned defaults are resolved through simctl at execution, never here.
          // targetName is null now, so each lookup waits until Gradle reads the input.
          fun pinned(pick: (PlatformSettings) -> Provider<String>): Provider<String> = providers.provider {
            families.get()[task.targetName]?.let(Platform::of)?.let { pick(extension.of(it)).orNull }
          }
          task.inputs.property("simsteward.deviceType", pinned { it.deviceType }).optional(true)
          task.inputs.property("simsteward.runtime", pinned { it.runtime }).optional(true)
          task.inputs.property("simsteward.slim", extension.slim)
          // Content only, so moving the profile does not rerun the task.
          task.inputs.files(pinned { it.profile }.map { file(it) })
            .withPropertyName("simsteward.profile")
            .withPathSensitivity(PathSensitivity.NONE)
            .optional()
          task.doFirst(LeaseAction(
            leases,
            name,
            families,
            extension,
            xcrun,
            simslim,
            projectDir,
            Ownership(recordsDir, buildRoot),
          ))
          task.doLast(ReleaseAction(leases))
        }
      }
    }
  }
}

/** The manual counterpart of the reaping that runs before a build's first lease. */
abstract class ReapTask : DefaultTask() {
  @get:Internal abstract val service: Property<LeaseService>
  @get:Input abstract val xcrun: Property<String>
  @get:Internal abstract val recordsDir: DirectoryProperty

  @TaskAction
  fun reap() = service.get().reap(xcrun.get(), recordsDir.get().asFile)
}

/**
 * Takes the task's lease when it actually runs. The task returns it when its tests end, through
 * [ReleaseAction] or, when KGP's action throws on a test failure, the root suite's `afterSuite`.
 * [LeaseService.onFinish] is the late backstop for anything else.
 */
private class LeaseAction(
  private val leases: Provider<LeaseService>,
  private val name: String,
  private val families: Provider<Map<String, Family>>,
  private val extension: SimstewardExtension,
  private val xcrun: Provider<String>,
  private val simslim: Provider<String>,
  private val projectDir: File,
  private val ownership: Ownership,
) : Action<Task> {
  override fun execute(task: Task) {
    task as KotlinNativeSimulatorTest
    val family = families.get().getValue(checkNotNull(task.targetName))
    val platform = Platform.of(family) ?: return
    val service = leases.get()
    val settings = extension.of(platform)
    val slimming = Slimming(extension.slim.get(), simslim.get(), settings.profile.orNull?.let { projectDir.resolve(it).absolutePath })
    service.lease(
      task.path,
      name,
      service.resolve(platform, settings.runtime.orNull, settings.deviceType.orNull, xcrun.get(), slimming),
      ownership,
      checkMaxSimulators(extension.maxSimulators.get()),
    )
    // Added at execution, so the listener never enters the configuration cache.
    task.addTestListener(object : TestListener {
      override fun beforeSuite(suite: TestDescriptor) = Unit
      override fun beforeTest(testDescriptor: TestDescriptor) = Unit
      override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) = Unit
      override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        if (suite.parent == null) service.release(task.path)
      }
    })
  }
}

private class ReleaseAction(private val leases: Provider<LeaseService>) : Action<Task> {
  override fun execute(task: Task) = leases.get().release(task.path)
}

private fun parseMaxSimulators(text: String): Int =
  text.toIntOrNull() ?: throw GradleException("simsteward.maxSimulators must be a whole number, but was '$text'.")

/** The one place a cap is range-checked, when it is read, whether it came from the property or the extension. */
private fun checkMaxSimulators(value: Int): Int =
  value.also { if (it < 1) throw GradleException("maxSimulators must be at least 1, but was $it.") }

internal const val NAME_PREFIX = "simsteward"

private fun sha256(value: String): String =
  HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
