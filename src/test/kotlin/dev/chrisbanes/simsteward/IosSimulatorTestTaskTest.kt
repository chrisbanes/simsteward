// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class IosSimulatorTestTaskTest {
  @TempDir
  lateinit var directory: File

  private val task = ":lib:iosSimulatorArm64Test"

  @Test
  fun testTaskReceivesConstantDeviceNameAndStandaloneOff() {
    val first = FixtureBuild(directory.resolve("one"))
    val other = FixtureBuild(directory.resolve("two"))

    val device = first.build(task).fixtureLine().device
    first.root.resolve("lib/build").deleteRecursively()
    val again = first.build(task, "--rerun-tasks").fixtureLine()
    val otherDevice = other.build(task).fixtureLine().device

    assertTrue(Regex("simsteward-[0-9a-f]{8}:lib:iosSimulatorArm64Test").matches(device), device)
    assertEquals(device, again.device)
    assertEquals("false", again.standalone)
    assertNotEquals(device, otherDevice)
  }

  @Test
  fun passingTaskLeasesOneManagedSimulatorThatIsDeletedAtTheEnd() {
    val build = FixtureBuild(directory)
    val device = build.build(task).fixtureLine().device

    assertLeaseLifecycle(build.commands(), device)
  }

  @Test
  fun failingTaskStillReturnsLeaseAndDeletesSimulator() {
    val build = FixtureBuild(directory)
    val result = build.buildAndFail(task, "-Pfixture.failTest")

    assertTrue("fixture test failure" in result.output)
    assertLeaseLifecycle(build.commands(), result.fixtureLine().device)
  }

  @Test
  fun unchangedRerunIsUpToDateReusesConfigurationCacheAndIssuesNoSimulatorCommands() {
    val build = FixtureBuild(directory)
    build.build(task)
    build.clearCommands()

    val result = build.build(task)

    assertEquals(TaskOutcome.UP_TO_DATE, result.task(task)?.outcome)
    assertTrue("Reusing configuration cache." in result.output, result.output)
    assertEquals(emptyList(), build.commands())
  }

  @Test
  fun changingThePinnedRuntimeOrDeviceTypeReRunsTheTask() {
    val build = FixtureBuild(directory)
    build.build(task)

    val runtime = build.build(task, "-Psimsteward.ios.runtime=${FixtureBuild.RUNTIME}-other")
    val deviceType = build.build(task, "-Psimsteward.ios.deviceType=${FixtureBuild.DEVICE_TYPE}-other")

    assertEquals(TaskOutcome.SUCCESS, runtime.task(task)?.outcome)
    assertEquals(TaskOutcome.SUCCESS, deviceType.task(task)?.outcome)
  }

  @Test
  fun changingSlimmingReRunsTheTask() {
    val build = FixtureBuild(directory)
    build.build(task)

    assertEquals(TaskOutcome.SUCCESS, build.build(task, SLIM).task(task)?.outcome)
  }

  @Test
  fun editingTheProfileContentsReRunsTheTaskButAnUnchangedProfileDoesNot() {
    val build = FixtureBuild(directory)
    val profile = directory.resolve("profile.json").apply { writeText("{}") }
    val profileArg = "-Psimsteward.ios.profile=${profile.absolutePath}"
    build.build(task, profileArg)
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(task, profileArg).task(task)?.outcome)

    profile.writeText("{\"edited\": true}")

    assertEquals(TaskOutcome.SUCCESS, build.build(task, profileArg).task(task)?.outcome)
  }

  @Test
  fun unpinnedSettingsAreResolvedDuringUpToDateCheck() {
    val build = FixtureBuild(directory, pinned = false)
    build.build(task)
    build.clearCommands()

    val result = build.build(task)

    assertEquals(TaskOutcome.UP_TO_DATE, result.task(task)?.outcome)
    assertEquals(listOf("xcrun simctl list -j runtimes"), build.commands())
  }

  @Test
  fun taskSkippedByOnlyIfCreatesNoSimulator() {
    val build = FixtureBuild(directory)
    build.appendToLib("tasks.named(\"iosSimulatorArm64Test\") { onlyIf { false } }")

    val result = build.build(task)

    assertEquals(TaskOutcome.SKIPPED, result.task(task)?.outcome)
    assertEquals(emptyList(), build.commands())
  }

  @Test
  fun failedSimulatorCommandFailsBuildWithCommandExitCodeAndStderr() {
    val build = FixtureBuild(directory)
    build.failOn("simctl bootstatus")

    val result = build.buildAndFail(task)

    val udid = FixtureBuild.UDID
    assertTrue("exit code 3" in result.output, result.output)
    assertTrue("${build.root.resolve("bin/xcrun").absolutePath} simctl bootstatus $udid -b" in result.output)
    assertTrue("line one of xcrun output\nxcrun: injected failure for simctl bootstatus $udid -b" in result.output)
    assertEquals("xcrun simctl delete $udid", build.commands().last())
  }

  @Test
  fun slimRunsOnThenVerifyBeforeFirstLeaseWithoutBootstatus() {
    val build = FixtureBuild(directory)
    val device = build.build(task, SLIM).fixtureLine().device

    val udid = FixtureBuild.UDID
    val pool = poolName(build.commands())
    assertEquals(
      listOf(
        "xcrun simctl list devices",
        "xcrun simctl create $pool ${FixtureBuild.DEVICE_TYPE} ${FixtureBuild.RUNTIME}",
        "simslim on $udid",
        "simslim verify $udid",
        "xcrun simctl list devices",
        "xcrun simctl rename $udid $device",
        "test $task",
        "xcrun simctl rename $udid $pool",
        "xcrun simctl delete $udid",
      ),
      build.commands(),
    )
  }

  @Test
  fun profileIsPassedToOnAndVerify() {
    val build = FixtureBuild(directory)
    build.build(task, SLIM, "-Psimsteward.ios.profile=/profiles/ios.json")

    val udid = FixtureBuild.UDID
    val commands = build.commands()
    assertTrue("simslim on $udid --profile /profiles/ios.json" in commands, "$commands")
    assertTrue("simslim verify $udid --profile /profiles/ios.json" in commands, "$commands")
  }

  @Test
  fun stockByDefaultRunsNoSimslimAndWorksWithoutIt() {
    val build = FixtureBuild(directory)
    val missing = directory.resolve("no-such-simslim").absolutePath

    build.build(task, "-Psimsteward.simslim=$missing")

    assertTrue(build.commands().none { it.startsWith("simslim") }, "${build.commands()}")
  }

  @Test
  fun missingSimslimFailsBuildAndStillDeletesSimulator() {
    val build = FixtureBuild(directory)
    val missing = directory.resolve("no-such-simslim").absolutePath

    val result = build.buildAndFail(task, SLIM, "-Psimsteward.simslim=$missing")

    assertTrue("Could not start $missing. Install SimSlim" in result.output, result.output)
    assertTrue("set simsteward.simslim to its full path" in result.output, result.output)
    assertEquals("xcrun simctl delete ${FixtureBuild.UDID}", build.commands().last())
    assertTrue(build.commands().none { it.startsWith("test") })
  }

  @Test
  fun failedOnOrVerifyFailsBuildAndStillDeletesSimulator() {
    for (step in listOf("on", "verify")) {
      val build = FixtureBuild(directory.resolve(step))
      build.failOn(step)

      val result = build.buildAndFail(task, SLIM)

      assertTrue("exit code 3" in result.output, result.output)
      assertTrue("simslim: injected failure for $step ${FixtureBuild.UDID}" in result.output, result.output)
      assertEquals("xcrun simctl delete ${FixtureBuild.UDID}", build.commands().last())
      assertTrue(build.commands().none { it.startsWith("test") })
    }
  }

  private fun poolName(commands: List<String>) =
    commands.firstNotNullOfOrNull { Regex("xcrun simctl create (simsteward-\\S+) ").find(it) }?.groupValues?.get(1)
      ?: error("First command is not a managed create: $commands")

  private companion object {
    const val SLIM = "-Psimsteward.slim=true"
  }

  private fun assertLeaseLifecycle(commands: List<String>, device: String) {
    val udid = FixtureBuild.UDID
    val pool = commands.firstNotNullOfOrNull { Regex("xcrun simctl create (simsteward-\\S+) ").find(it) }?.groupValues?.get(1)
      ?: error("First command is not a managed create: $commands")
    assertNotEquals(device, pool)
    assertEquals(
      listOf(
        "xcrun simctl list devices",
        "xcrun simctl create $pool ${FixtureBuild.DEVICE_TYPE} ${FixtureBuild.RUNTIME}",
        "xcrun simctl bootstatus $udid -b",
        "xcrun simctl list devices",
        "xcrun simctl rename $udid $device",
        "test $task",
        "xcrun simctl rename $udid $pool",
        "xcrun simctl delete $udid",
      ),
      commands,
    )
  }

  private data class FixtureLine(val device: String, val standalone: String)

  private fun org.gradle.testkit.runner.BuildResult.fixtureLine(): FixtureLine {
    val match = Regex("fixture: $task device=(\\S+) standalone=(\\S+)").find(output)
      ?: error("No fixture line in output:\n$output")
    return FixtureLine(match.groupValues[1], match.groupValues[2])
  }
}
