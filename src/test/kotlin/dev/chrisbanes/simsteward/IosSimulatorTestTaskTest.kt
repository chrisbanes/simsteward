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
