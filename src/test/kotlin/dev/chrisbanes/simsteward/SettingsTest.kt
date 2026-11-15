package dev.chrisbanes.simsteward

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class SettingsTest {
  @TempDir
  lateinit var directory: File

  private val lib = ":lib:iosSimulatorArm64Test"
  private val lib2 = ":lib2:iosSimulatorArm64Test"
  private val otherType = "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"

  private fun FixtureBuild.creates() = commands().filter { it.startsWith("xcrun simctl create") }

  @Test
  fun propertyAppliesToEveryProjectAndExtensionOverridesIt() {
    val build = FixtureBuild(directory)
    build.addModule("lib2")
    build.simsteward("lib2", "ios { deviceType.set(\"$otherType\") }")

    build.build(lib, lib2, "--no-parallel")

    val creates = build.creates()
    assertEquals(2, creates.size, creates.toString())
    assertTrue(creates.any { FixtureBuild.DEVICE_TYPE in it }, creates.toString())
    assertTrue(creates.any { otherType in it }, creates.toString())
  }

  @Test
  fun extensionRuntimeOverridesProperty() {
    val build = FixtureBuild(directory)
    build.simsteward("lib", "ios { runtime.set(\"com.apple.CoreSimulator.SimRuntime.iOS-26-4\") }")

    build.build(lib)

    assertTrue(build.creates().single().endsWith("iOS-26-4"), build.creates().toString())
  }

  @Test
  fun projectsWithEqualSpecsSharePoolAndDifferentProfilesDoNot() {
    // A cap of 1 keeps this about pool keys: task-completion events arrive asynchronously, so with a
    // higher cap the second task can lease before the first one's return lands and grow the pool.
    val build = FixtureBuild(directory)
    build.addModule("lib2")
    build.build(lib, lib2, "--no-parallel", "-Psimsteward.maxSimulators=1")
    assertEquals(1, build.creates().size)

    val split = FixtureBuild(directory.resolve("split"))
    split.addModule("lib2")
    split.simsteward("lib2", "ios { profile.set(\"other.json\") }")
    split.build(lib, lib2, "--no-parallel", "-Psimsteward.maxSimulators=1")
    assertEquals(2, split.creates().size)
  }

  @Test
  fun projectWithSimstewardDisabledKeepsKgpDefaultsAndCreatesNoSimulator() {
    val build = FixtureBuild(directory)
    build.simsteward("lib", "enabled.set(false)")

    val result = build.build(lib)

    assertEquals(emptyList(), build.commands().filter { it.startsWith("xcrun") })
    assertTrue("standalone=true" in result.output, result.output)
    assertFalse("device=simsteward-" in result.output, result.output)
  }

  @Test
  fun propertyDisablesEveryProjectAndOnlyThatPropertyDoes() {
    val build = FixtureBuild(directory)
    build.addModule("lib2")

    build.build(lib, lib2, "-Psimsteward.enabled=false")

    assertEquals(emptyList(), build.commands().filter { it.startsWith("xcrun") })
  }

  @Test
  fun simstewardLeavesTaskEnabledStateAlone() {
    val build = FixtureBuild(directory)
    val result = build.build(lib)
    assertTrue("enabled=true" in result.output, result.output)

    val off = FixtureBuild(directory.resolve("off"))
    off.appendToLib("tasks.named(\"iosSimulatorArm64Test\") { enabled = false }")
    val skipped = off.build(lib)
    assertEquals(TaskOutcome.SKIPPED, skipped.task(lib)?.outcome)
    assertEquals(emptyList(), off.commands())
  }

  @Test
  fun worksWithIsolatedProjectsAndConfigurationCache() {
    val build = FixtureBuild(directory)
    build.addModule("lib2")
    build.simsteward("lib2", "ios { deviceType.set(\"$otherType\") }")

    build.build(lib, lib2, "--no-parallel", "-Dorg.gradle.unsafe.isolated-projects=true")
    val again = build.build(lib, lib2, "--no-parallel", "-Dorg.gradle.unsafe.isolated-projects=true", "--rerun-tasks")

    assertTrue("Reusing configuration cache." in again.output, again.output)
  }
}
