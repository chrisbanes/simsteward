package dev.chrisbanes.simsteward

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.io.TempDir

class PoolGrowthTest {
  @TempDir
  lateinit var directory: File

  private val parallel = arrayOf("--parallel", "--max-workers=4")

  @Test
  fun parallelTasksInTwoModulesLeaseTwoDistinctSimulators() {
    val build = FixtureBuild(directory)
    build.addModule("second")

    val result = build.build(*tasks("lib", "second"), *parallel, "-Pfixture.holders=2")

    val renames = build.leaseRenames()
    assertEquals(2, build.creates().size, build.commands().toString())
    assertEquals(setOf(result.device("lib"), result.device("second")), renames.values.toSet())
    assertEquals(2, renames.keys.size, renames.toString())
    for ((udid, device) in renames) {
      val task = device.substringAfter(":").let { ":$it" }
      assertTrue("simsteward: $task leased managed simulator $udid" in result.output, result.output)
    }
  }

  @Test
  fun thirdParallelTaskWaitsForAReturnedLeaseInsteadOfCreatingAThirdSimulator() {
    val build = FixtureBuild(directory)
    build.addModule("second")
    build.addModule("third")

    val result = build.build(
      *tasks("lib", "second", "third"), *parallel, "-Pfixture.holders=2", "-Pfixture.awaitWaiter",
    )

    assertEquals(2, build.creates().size, build.commands().toString())
    assertEquals(3, build.commands().count { it.startsWith("test ") }, build.commands().toString())
    assertTrue(
      Regex("simsteward: :\\w+:iosSimulatorArm64Test is waiting for a returned lease: its pool is at its cap of 2 simulators")
        .containsMatchIn(result.output),
      result.output,
    )
  }

  @Test
  fun buildWithOneSimulatorTestTaskCreatesOneSimulator() {
    val build = FixtureBuild(directory)
    build.addModule("second")

    build.build(":lib:iosSimulatorArm64Test", *parallel)

    assertEquals(1, build.creates().size, build.commands().toString())
  }

  @Test
  fun maxSimulatorsPropertyCapsThePool() {
    val build = FixtureBuild(directory)
    build.addModule("second")

    val result = build.build(
      *tasks("lib", "second"), *parallel, "-Psimsteward.maxSimulators=1", "-Pfixture.holders=1", "-Pfixture.awaitWaiter",
    )

    assertEquals(1, build.creates().size, build.commands().toString())
    assertTrue("its pool is at its cap of 1 simulator" in result.output, result.output)
  }

  @Test
  fun invalidMaxSimulatorsFailsWithClearMessage() {
    val build = FixtureBuild(directory)

    val result = build.buildAndFail(":lib:iosSimulatorArm64Test", "-Psimsteward.maxSimulators=0")

    assertTrue("simsteward.maxSimulators must be a whole number of at least 1, but was '0'" in result.output, result.output)
    assertEquals(emptyList(), build.creates())
  }

  @Test
  fun leaseFailsWhenAnotherSimulatorAlreadyHasTheTasksName() {
    val build = FixtureBuild(directory)
    val device = build.build(":lib:iosSimulatorArm64Test").device("lib")
    build.root.resolve("lib/build").deleteRecursively()
    build.clearCommands()
    build.listDevices(device to OTHER_UDID)

    val result = build.buildAndFail(":lib:iosSimulatorArm64Test", "--rerun-tasks")

    assertTrue(
      "Another simulator, $OTHER_UDID, is already named $device, so simctl could pick either one. " +
        "Delete or rename that simulator." in result.output,
      result.output,
    )
    assertTrue("test :lib:iosSimulatorArm64Test" !in build.commands(), build.commands().toString())
    assertTrue(build.commands().last().startsWith("xcrun simctl delete "), build.commands().toString())
  }

  @Test
  fun failedTaskReturnsItsLeaseToAWaitingTask() {
    val build = FixtureBuild(directory)
    build.addModule("second")

    val result = build.buildAndFail(
      *tasks("lib", "second"), *parallel, "--continue",
      "-Psimsteward.maxSimulators=1", "-Pfixture.holders=1", "-Pfixture.awaitWaiter", "-Pfixture.failTest",
    )

    val udid = FixtureBuild.UDID
    assertEquals(1, build.creates().size, build.commands().toString())
    assertEquals(
      listOf(result.device("lib"), result.device("second")).sorted(),
      build.commands().mapNotNull { Regex("xcrun simctl rename $udid (\\S+:\\S+)").find(it)?.groupValues?.get(1) }.sorted(),
    )
    assertEquals(2, build.commands().count { it.startsWith("test ") }, build.commands().toString())
    assertTrue("fixture test failure" in result.output, result.output)
  }

  private companion object {
    const val OTHER_UDID = "0A1B2C3D-0000-4000-8000-000000000001"
  }

  private fun tasks(vararg modules: String) = modules.map { ":$it:iosSimulatorArm64Test" }.toTypedArray()

  private fun FixtureBuild.creates() = commands().filter { it.startsWith("xcrun simctl create ") }

  /** UDID to device name, for each rename to a task's name. */
  private fun FixtureBuild.leaseRenames(): Map<String, String> = commands()
    .mapNotNull { Regex("xcrun simctl rename (\\S+) (\\S+:\\S+)").find(it)?.destructured }
    .associate { (udid, device) -> udid to device }

  private fun BuildResult.device(module: String): String =
    Regex("fixture: :$module:iosSimulatorArm64Test device=(\\S+)").find(output)?.groupValues?.get(1)
      ?: error("No fixture line for $module in output:\n$output")
}
