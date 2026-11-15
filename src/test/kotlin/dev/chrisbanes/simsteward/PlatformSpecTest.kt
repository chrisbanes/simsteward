// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PlatformSpecTest {
  @TempDir
  lateinit var directory: File

  private val dt = "com.apple.CoreSimulator.SimDeviceType"
  private val rt = "com.apple.CoreSimulator.SimRuntime"

  private fun creates(build: FixtureBuild) =
    build.creates().map { it.split(" ").drop(4) }

  @Test
  fun eachPlatformLeasesASimulatorOfItsOwnPlatform() {
    val build = FixtureBuild(
      directory,
      pinned = false,
      targets = listOf("iosSimulatorArm64()", "tvosSimulatorArm64()", "watchosSimulatorArm64()"),
    )

    build.build(":lib:iosSimulatorArm64Test", ":lib:tvosSimulatorArm64Test", ":lib:watchosSimulatorArm64Test")

    assertEquals(
      setOf(
        listOf("$dt.iPhone-17", "$rt.iOS-27-0"),
        listOf("$dt.Apple-TV-4K", "$rt.tvOS-27-0"),
        listOf("$dt.Apple-Watch-Series-11", "$rt.watchOS-27-0"),
      ),
      creates(build).toSet(),
    )
  }

  @Test
  fun renamedTargetStillMapsToItsPlatform() {
    val build = FixtureBuild(directory, pinned = false, targets = listOf("tvosSimulatorArm64(\"foo\")"))

    build.build(":lib:fooTest")

    assertEquals(listOf(listOf("$dt.Apple-TV-4K", "$rt.tvOS-27-0")), creates(build))
  }

  @Test
  fun pinnedValueOverridesEitherPick() {
    val build = FixtureBuild(directory, pinned = false)
    build.root.resolve("gradle.properties").appendText("simsteward.ios.runtime=$rt.iOS-26-4\n")

    build.build(":lib:iosSimulatorArm64Test")

    assertEquals(listOf(listOf("$dt.iPhone-16", "$rt.iOS-26-4")), creates(build))
  }

  @Test
  fun newestRuntimeComparesEachVersionComponent() {
    val build = FixtureBuild(directory, pinned = false)
    build.installRuntimes(
      FixtureBuild.runtimesJson(
        FixtureBuild.runtime("iOS", "18.3.1", "iPhone", "iPhone 16"),
        FixtureBuild.runtime("iOS", "18.4", "iPhone", "iPhone 16"),
        FixtureBuild.runtime("iOS", "17.6.1", "iPhone", "iPhone 16"),
      ),
    )

    build.build(":lib:iosSimulatorArm64Test")

    assertEquals(listOf(listOf("$dt.iPhone-16", "$rt.iOS-18-4")), creates(build))
  }

  @Test
  fun missingRuntimeFailsNamingPlatformAndWhatToInstall() {
    val build = FixtureBuild(directory, pinned = false, targets = listOf("watchosSimulatorArm64()"))
    build.installRuntimes(FixtureBuild.runtimesJson(FixtureBuild.runtime("iOS", "27.0", "iPhone", "iPhone 17")))

    val result = build.buildAndFail(":lib:watchosSimulatorArm64Test")

    assertTrue("No installed watchOS simulator runtime" in result.output, result.output)
    assertTrue("Install a watchOS simulator runtime" in result.output, result.output)
    assertEquals(emptyList(), creates(build))
  }

  @Test
  fun noSimctlLookupsDuringConfiguration() {
    val build = FixtureBuild(directory, pinned = false)

    build.build("help")

    assertEquals(emptyList(), build.commands())
  }
}
