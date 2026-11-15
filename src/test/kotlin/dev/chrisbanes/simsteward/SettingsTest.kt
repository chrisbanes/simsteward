// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

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
    val build = FixtureBuild(directory)
    build.addModule("lib2")
    // One worker, because the configuration cache runs tasks in parallel even with --no-parallel.
    build.build(lib, lib2, "--max-workers=1")
    assertEquals(1, build.creates().size)

    val split = FixtureBuild(directory.resolve("split"))
    split.addModule("lib2")
    split.simsteward("lib2", "ios { profile.set(\"other.json\") }")
    split.build(lib, lib2, "--max-workers=1")
    assertEquals(2, split.creates().size)
  }

  @Test
  fun relativeProfilesResolveAgainstTheirOwnProjectAndPropertyAgainstTheBuildRoot() {
    val build = FixtureBuild(directory)
    build.addModule("lib2")
    build.addModule("lib3")
    val libProfile = directory.resolve("lib/profile.json").apply { writeText("{\"lib\": true}") }
    val lib2Profile = directory.resolve("lib2/profile.json").apply { writeText("{\"lib2\": true}") }
    val rootProfile = directory.resolve("root.json").apply { writeText("{}") }
    build.simsteward("lib", "ios { profile.set(\"profile.json\") }")
    build.simsteward("lib2", "ios { profile.set(\"profile.json\") }")

    build.build(lib, lib2, ":lib3:iosSimulatorArm64Test", "-Psimsteward.ios.profile=root.json", "--max-workers=1")

    val ons = build.commands().filter { it.startsWith("simslim on ") }
    assertEquals(3, ons.size, ons.toString())
    listOf(libProfile, lib2Profile, rootProfile).forEach { profile ->
      assertTrue(ons.any { it.endsWith("--profile ${profile.canonicalPath}") || it.endsWith("--profile ${profile.absolutePath}") }, "$profile not in $ons")
    }
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
