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

class ResolvedSpecUpToDateTest {
  @TempDir lateinit var directory: File
  private val task = ":lib:iosSimulatorArm64Test"
  private fun state(build: FixtureBuild, module: String = "lib") =
    build.root.resolve("$module/build/simsteward/iosSimulatorArm64Test/resolved-identifiers.properties")
  private fun newer(build: FixtureBuild) = build.installRuntimes(
    FixtureBuild.runtimesJson(FixtureBuild.runtime("iOS", "28.0", "iPhone", "iPhone 18")),
  )

  @Test fun unpinnedRuntimeChangeUsesSuccessfulTaskState() {
    val build = FixtureBuild(directory, pinned = false)
    assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
    val record = state(build).readBytes().toList()
    build.clearCommands()
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
    assertEquals(record, state(build).readBytes().toList())
    newer(build)
    build.clearCommands()
    assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
    assertTrue(build.creates().single().contains("iPhone-18 com.apple.CoreSimulator.SimRuntime.iOS-28-0"))
    assertEquals("com.apple.CoreSimulator.SimRuntime.iOS-28-0", readRecord(state(build))["runtime"])
    build.clearCommands()
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
  }

  @Test fun failedTaskDoesNotAdvanceResolvedState() {
    val build = FixtureBuild(directory, pinned = false)
    val marker = directory.resolve("late-failure")
    build.appendToLib(
      """
      afterEvaluate {
        tasks.named("iosSimulatorArm64Test") {
          doLast { if (File("${marker.absolutePath}").exists()) throw GradleException("late failure") }
        }
      }
    """,
    )
    build.build(task)
    val before = state(build).readBytes().toList()
    newer(build)
    val failed = build.buildAndFail(task, "-Pfixture.failTest")
    assertEquals(TaskOutcome.FAILED, failed.task(task)?.outcome)
    assertTrue("fixture test failure" in failed.output)
    assertEquals(before, state(build).readBytes().toList())
    marker.writeText("")
    val late = build.buildAndFail(task)
    assertEquals(TaskOutcome.FAILED, late.task(task)?.outcome)
    assertTrue("late failure" in late.output)
    assertEquals(before, state(build).readBytes().toList())
    marker.delete()
    assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
  }

  @Test fun firstFailureDoesNotCreateState() {
    val build = FixtureBuild(directory, pinned = false)
    assertEquals(TaskOutcome.FAILED, build.buildAndFail(task, "-Pfixture.failTest").task(task)?.outcome)
    assertFalse(state(build).exists())
  }

  @Test fun successfulTaskCommitsWhenAnotherTaskFails() {
    val build = FixtureBuild(directory, pinned = false)
    build.addModule("lib2")
    val marker = directory.resolve("fail-second")
    build.appendTo("lib2", "afterEvaluate { tasks.named(\"iosSimulatorArm64Test\") { doLast { if (File(\"${marker.absolutePath}\").exists()) throw GradleException(\"second failed\") } } }")
    val second = ":lib2:iosSimulatorArm64Test"
    build.build(task, second, "--max-workers=1")
    val before = state(build, "lib2").readBytes().toList()
    newer(build)
    marker.writeText("")
    val result = build.buildAndFail(task, second, "--continue", "--max-workers=1")
    assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome)
    assertEquals(TaskOutcome.FAILED, result.task(second)?.outcome)
    assertEquals("com.apple.CoreSimulator.SimRuntime.iOS-28-0", readRecord(state(build))["runtime"])
    assertEquals(before, state(build, "lib2").readBytes().toList())
  }

  @Test fun deviceDriftAndMetadataChanges() {
    val build = FixtureBuild(directory, pinned = false)
    build.build(task)
    val changed = FixtureBuild.runtime("iOS", "27.0", "iPhone", "iPhone 17 Pro", "iPhone 17")
    build.installRuntimes(FixtureBuild.runtimesJson(changed))
    build.clearCommands()
    assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
    assertTrue(build.creates().single().contains("iPhone-17-Pro com.apple.CoreSimulator.SimRuntime.iOS-27-0"))
    build.installRuntimes(FixtureBuild.runtimesJson(changed.replace("\"isAvailable\"", "\"buildversion\": \"different\", \"isAvailable\"")))
    build.clearCommands()
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
  }

  @Test fun resolutionFailureIsReportedByLeaseAndRetriedNextBuild() {
    val build = FixtureBuild(directory, pinned = false)
    build.build(task)
    val before = state(build).readBytes().toList()
    build.failOn("simctl list -j runtimes")
    build.clearCommands()
    val failed = build.buildAndFail(task)
    assertEquals(TaskOutcome.FAILED, failed.task(task)?.outcome)
    assertTrue("exit code 3" in failed.output && "injected failure" in failed.output)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
    assertEquals(before, state(build).readBytes().toList())
    build.root.resolve("fail-on").delete()
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
  }

  @Test fun staleStateIsRepaired() {
    val build = FixtureBuild(directory, pinned = false)
    build.build(task)
    val record = state(build)
    val valid = record.readText()
    val corruptions = listOf<String?>(null, "bad=\\uXXXX", "runtime=missing", valid + "extra=value\n", valid.replace("iOS-27-0", "iOS-1-0"))
    corruptions.forEach { content ->
      if (content == null) record.delete() else record.writeText(content)
      assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
      assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
    }
  }

  @Test fun pinnedHalvesPreserveSelection() {
    val runtimePin = FixtureBuild(directory.resolve("runtime"), pinned = false)
    runtimePin.root.resolve("gradle.properties").appendText("simsteward.ios.runtime=${FixtureBuild.RUNTIME}\n")
    runtimePin.build(task)
    runtimePin.installRuntimes(
      FixtureBuild.runtimesJson(
        FixtureBuild.runtime("iOS", "27.0", "iPhone", "iPhone 17"),
        FixtureBuild.runtime("iOS", "28.0", "iPhone", "iPhone 18"),
      ),
    )
    assertEquals(TaskOutcome.UP_TO_DATE, runtimePin.build(task).task(task)?.outcome)
    runtimePin.installRuntimes(
      FixtureBuild.runtimesJson(
        FixtureBuild.runtime("iOS", "27.0", "iPhone", "iPhone 17 Pro"),
        FixtureBuild.runtime("iOS", "28.0", "iPhone", "iPhone 18"),
      ),
    )
    runtimePin.clearCommands()
    assertEquals(TaskOutcome.SUCCESS, runtimePin.build(task).task(task)?.outcome)
    assertTrue(runtimePin.creates().single().contains("iPhone-17-Pro ${FixtureBuild.RUNTIME}"))
    assertEquals(TaskOutcome.UP_TO_DATE, runtimePin.build(task).task(task)?.outcome)

    val devicePin = FixtureBuild(directory.resolve("device"), pinned = false)
    devicePin.root.resolve("gradle.properties").appendText("simsteward.ios.deviceType=${FixtureBuild.DEVICE_TYPE}\n")
    devicePin.build(task)
    devicePin.installRuntimes(
      FixtureBuild.runtimesJson(
        FixtureBuild.runtime("iOS", "28.0", "iPhone", "iPhone 18", "iPhone 17"),
        FixtureBuild.runtime("iOS", "29.0", "iPhone", "iPhone 19"),
      ),
    )
    devicePin.clearCommands()
    assertEquals(TaskOutcome.SUCCESS, devicePin.build(task).task(task)?.outcome)
    assertTrue(devicePin.creates().single().contains("${FixtureBuild.DEVICE_TYPE} com.apple.CoreSimulator.SimRuntime.iOS-28-0"))
    assertEquals(TaskOutcome.UP_TO_DATE, devicePin.build(task).task(task)?.outcome)
  }

  @Test fun namePinsAreResolvedAndRechecked() {
    val build = FixtureBuild(directory, pinned = false)
    build.root.resolve("gradle.properties").appendText("simsteward.ios.deviceType=iPhone 17\nsimsteward.ios.runtime=iOS 27.0\n")
    assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
    assertEquals(mapOf("runtime" to FixtureBuild.RUNTIME, "deviceType" to FixtureBuild.DEVICE_TYPE), readRecord(state(build)))
    build.clearCommands()
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
    build.installRuntimes(
      FixtureBuild.runtimesJson(FixtureBuild.runtime("iOS", "27.0", "iPhone", "iPhone 17").replace("iOS-27-0", "iOS-27-0-alternate")),
    )
    assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
    assertEquals("${FixtureBuild.RUNTIME}-alternate", readRecord(state(build))["runtime"])
  }

  @Test fun sharedResolutionFailureIsMemoizedAcrossTasks() {
    val build = FixtureBuild(directory, pinned = false)
    build.addModule("lib2")
    val second = ":lib2:iosSimulatorArm64Test"
    build.build(task, second, "--max-workers=1")
    val firstState = state(build).readBytes().toList()
    val secondState = state(build, "lib2").readBytes().toList()
    build.failOn("simctl list -j runtimes")
    build.clearCommands()
    val result = build.buildAndFail(task, second, "--continue", "--max-workers=1")
    assertEquals(TaskOutcome.FAILED, result.task(task)?.outcome)
    assertEquals(TaskOutcome.FAILED, result.task(second)?.outcome)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
    assertEquals(firstState, state(build).readBytes().toList())
    assertEquals(secondState, state(build, "lib2").readBytes().toList())
  }

  @Test fun invalidInventoriesFailDuringExecutionAndRecover() {
    val build = FixtureBuild(directory, pinned = false)
    build.build(task)
    val saved = state(build).readBytes().toList()
    val row = FixtureBuild.runtime("iOS", "28.0", "iPhone", "iPhone 18")
    val invalid = listOf(
      FixtureBuild.runtimesJson() to "No installed iOS simulator runtime",
      FixtureBuild.runtimesJson(row.replace("\"version\": \"28.0\"", "\"version\": \"bad\"")) to "Invalid runtime version",
      FixtureBuild.runtimesJson(row, row.replace("iOS-28-0", "iOS-alternate")) to "Multiple iOS runtime identifiers",
    )
    invalid.forEach { (json, error) ->
      build.installRuntimes(json)
      build.clearCommands()
      val result = build.buildAndFail(task)
      assertEquals(TaskOutcome.FAILED, result.task(task)?.outcome)
      assertTrue(error in result.output, result.output)
      assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
      assertEquals(saved, state(build).readBytes().toList())
    }
    build.installRuntimes(FixtureBuild.RUNTIMES_JSON)
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
  }

  @Test fun failedStateWriteDoesNotFailTestAndRecovers() {
    val build = FixtureBuild(directory, pinned = false)
    val record = state(build)
    record.mkdirs()
    val result = build.build(task)
    assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome)
    assertTrue("Could not save resolved identifiers" in result.output, result.output)
    record.deleteRecursively()
    assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
  }

  @Test fun duplicateMetadataAndOrdinaryInputChanges() {
    val build = FixtureBuild(directory, pinned = false)
    build.build(task)
    val row = FixtureBuild.runtime("iOS", "27.0", "iPhone", "iPhone 17")
    val duplicate = row.replace("\"isAvailable\"", "\"buildversion\": \"other\", \"isAvailable\"")
    build.installRuntimes(FixtureBuild.runtimesJson(row, duplicate))
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task).task(task)?.outcome)
    build.root.resolve("lib/test.kexe").writeText("changed")
    assertEquals(TaskOutcome.SUCCESS, build.build(task).task(task)?.outcome)
  }

  @Test fun sharedSuccessfulResolutionUsesOneListing() {
    val build = FixtureBuild(directory, pinned = false)
    build.addModule("lib2")
    build.simsteward("lib2", "slim.set(true)")
    val second = ":lib2:iosSimulatorArm64Test"
    val first = build.build(task, second, "--max-workers=1")
    assertEquals(TaskOutcome.SUCCESS, first.task(task)?.outcome)
    assertEquals(TaskOutcome.SUCCESS, first.task(second)?.outcome)
    assertEquals(1, build.commands().count { it == "xcrun simctl list -j runtimes" })
    build.clearCommands()
    val again = build.build(task, second, "--max-workers=1")
    assertEquals(TaskOutcome.UP_TO_DATE, again.task(task)?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, again.task(second)?.outcome)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
  }

  @Test fun distinctPinsResolveSeparately() {
    val build = FixtureBuild(directory, pinned = false)
    build.addModule("lib2")
    build.simsteward("lib2", "ios { runtime.set(\"${FixtureBuild.RUNTIME}\") }")
    val second = ":lib2:iosSimulatorArm64Test"
    val result = build.build(task, second, "--max-workers=1")
    assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(second)?.outcome)
    assertEquals(2, build.commands().count { it == "xcrun simctl list -j runtimes" })
    assertTrue(build.creates().single().endsWith(FixtureBuild.RUNTIME))
  }

  @Test fun includedBuildKeepsResolvedState() {
    val build = FixtureBuild(directory.resolve("fixture"), pinned = false)
    directory.resolve("settings.gradle.kts").writeText(FixtureBuild.settings("outer") + "\nincludeBuild(\"fixture\")\n")
    build.root.resolve("gradle.properties").copyTo(directory.resolve("gradle.properties"))
    val included = ":fixture$task"
    fun run() = build.runner(included).withProjectDir(directory).build()
    run()
    assertTrue(state(build).exists(), "The finish event must find the included task's staged state")
    build.clearCommands()
    run()
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
  }

  @Test fun parallelTasksShareResolution() {
    val build = FixtureBuild(directory, pinned = false)
    build.addModule("lib2")
    val second = ":lib2:iosSimulatorArm64Test"
    val result = build.build(task, second, "--parallel", "--max-workers=2", "-Pfixture.holders=2")
    assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(second)?.outcome)
    assertEquals(1, build.commands().count { it == "xcrun simctl list -j runtimes" })
    assertEquals(2, build.creates().size)
  }

  @Test fun configurationAndSkippedTasksNeverResolve() {
    val build = FixtureBuild(directory, pinned = false)
    val marker = directory.resolve("skip")
    build.appendToLib("tasks.named(\"iosSimulatorArm64Test\") { onlyIf { !File(\"${marker.absolutePath}\").exists() } }")
    build.build("help")
    val again = build.build("help")
    assertTrue("Reusing configuration cache." in again.output)
    build.build("help", "--no-configuration-cache")
    build.build(task, "--dry-run")
    assertEquals(emptyList(), build.commands())
    assertFalse(state(build).exists())
    marker.writeText("")
    assertEquals(TaskOutcome.SKIPPED, build.build(task).task(task)?.outcome)
    assertEquals(emptyList(), build.commands())
    assertFalse(state(build).exists())
    marker.delete()
    build.build(task)
    val before = state(build).readBytes().toList()
    build.clearCommands()
    marker.writeText("")
    assertEquals(TaskOutcome.SKIPPED, build.build(task).task(task)?.outcome)
    assertEquals(emptyList(), build.commands())
    assertEquals(before, state(build).readBytes().toList())
    val disabled = FixtureBuild(directory.resolve("disabled"), pinned = false)
    disabled.appendToLib("tasks.named(\"iosSimulatorArm64Test\") { enabled = false }")
    assertEquals(TaskOutcome.SKIPPED, disabled.build(task).task(task)?.outcome)
    assertEquals(emptyList(), disabled.commands())
    assertFalse(state(disabled).exists())
  }

  @Test fun isolatedProjectsReuseCacheAndDetectDrift() {
    val build = FixtureBuild(directory, pinned = false)
    build.addModule("lib2")
    val second = ":lib2:iosSimulatorArm64Test"
    val args = arrayOf(task, second, "--max-workers=1", "-Dorg.gradle.unsafe.isolated-projects=true")
    val first = build.build(*args)
    assertEquals(TaskOutcome.SUCCESS, first.task(task)?.outcome)
    assertEquals(TaskOutcome.SUCCESS, first.task(second)?.outcome)
    assertTrue(state(build).exists() && state(build, "lib2").exists())
    build.clearCommands()
    val again = build.build(*args)
    assertTrue("Reusing configuration cache." in again.output, again.output)
    assertEquals(TaskOutcome.UP_TO_DATE, again.task(task)?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, again.task(second)?.outcome)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
    newer(build)
    build.clearCommands()
    val changed = build.build(*args)
    assertTrue("Reusing configuration cache." in changed.output, changed.output)
    assertEquals(TaskOutcome.SUCCESS, changed.task(task)?.outcome)
    assertEquals(TaskOutcome.SUCCESS, changed.task(second)?.outcome)
    assertTrue(build.creates().all { it.endsWith("iOS-28-0") })
  }

  @Test fun otherPlatformAndRenamedTargetDetectDrift() {
    for ((target, taskName, platform, family, device) in listOf(
      listOf("iosSimulatorArm64(\"customIos\")", "customIosTest", "iOS", "iPhone", "iPhone 18"),
      listOf("tvosSimulatorArm64()", "tvosSimulatorArm64Test", "tvOS", "Apple TV", "Apple TV 5K"),
      listOf("watchosSimulatorArm64()", "watchosSimulatorArm64Test", "watchOS", "Apple Watch", "Apple Watch New"),
    )) {
      val build = FixtureBuild(directory.resolve(taskName), pinned = false, targets = listOf(target))
      val path = ":lib:$taskName"
      assertEquals(TaskOutcome.SUCCESS, build.build(path).task(path)?.outcome)
      build.installRuntimes(FixtureBuild.runtimesJson(FixtureBuild.runtime(platform, "28.0", family, device)))
      build.clearCommands()
      assertEquals(TaskOutcome.SUCCESS, build.build(path).task(path)?.outcome)
      assertTrue(build.creates().single().endsWith("$platform-28-0"))
      assertEquals(TaskOutcome.UP_TO_DATE, build.build(path).task(path)?.outcome)
    }
  }

  @Test fun isolatedPartialPinsKeepSeparateStateAndSelections() {
    val build = FixtureBuild(directory, pinned = false)
    build.addModule("lib2")
    build.simsteward("lib", "ios { runtime.set(\"${FixtureBuild.RUNTIME}\") }")
    build.simsteward("lib2", "ios { deviceType.set(\"${FixtureBuild.DEVICE_TYPE}\") }")
    val second = ":lib2:iosSimulatorArm64Test"
    val args = arrayOf(task, second, "--max-workers=1", "-Dorg.gradle.unsafe.isolated-projects=true")
    build.build(*args)
    build.installRuntimes(
      FixtureBuild.runtimesJson(
        FixtureBuild.runtime("iOS", "27.0", "iPhone", "iPhone 17 Pro", "iPhone 17"),
        FixtureBuild.runtime("iOS", "28.0", "iPhone", "iPhone 17"),
      ),
    )
    build.clearCommands()
    val result = build.build(*args)
    assertTrue("Reusing configuration cache." in result.output, result.output)
    assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(second)?.outcome)
    assertEquals(2, build.commands().count { it == "xcrun simctl list -j runtimes" })
    assertEquals(FixtureBuild.RUNTIME, readRecord(state(build))["runtime"])
    assertEquals("com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro", readRecord(state(build))["deviceType"])
    assertEquals("com.apple.CoreSimulator.SimRuntime.iOS-28-0", readRecord(state(build, "lib2"))["runtime"])
    assertEquals(FixtureBuild.DEVICE_TYPE, readRecord(state(build, "lib2"))["deviceType"])
  }

  @Test fun distinctPlatformsResolveIndependently() {
    val build = FixtureBuild(directory, pinned = false, targets = listOf("iosSimulatorArm64()", "tvosSimulatorArm64()"))
    val tv = ":lib:tvosSimulatorArm64Test"
    val result = build.build(task, tv, "--max-workers=1")
    assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(tv)?.outcome)
    assertEquals(2, build.commands().count { it == "xcrun simctl list -j runtimes" })
    assertTrue(build.creates().any { it.endsWith("iOS-27-0") })
    assertTrue(build.creates().any { it.endsWith("tvOS-27-0") })
  }
}
