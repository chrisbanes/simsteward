// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
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
      *tasks("lib", "second", "third"),
      *parallel,
      "-Pfixture.holders=2",
      "-Pfixture.awaitWaiter",
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
  fun sequentialTasksAtTheDefaultCapShareOneSimulator() {
    assertEquals(List(BUILDS) { 1 }, sequentialBuilds { build(*it) }.map { b -> b.count { it.startsWith("xcrun simctl create ") } })
  }

  @Test
  fun sequentialFailingTasksAtTheDefaultCapShareOneSimulator() {
    val builds = sequentialBuilds { buildAndFail(*it, "--continue", "-Pfixture.failTest") }
    assertEquals(List(BUILDS) { 1 }, builds.map { b -> b.count { it.startsWith("xcrun simctl create ") } })
    assertEquals(List(BUILDS) { SEQUENTIAL_MODULES.size }, builds.map { b -> b.count { it.startsWith("test ") } })
  }

  /**
   * Runs one task per module, one at a time, [BUILDS] times, and returns each build's commands.
   * Reruns reuse the configuration cache and run fast, which is when a late lease return showed.
   */
  private fun sequentialBuilds(run: FixtureBuild.(Array<String>) -> Unit): List<List<String>> {
    val build = FixtureBuild(directory)
    SEQUENTIAL_MODULES.drop(1).forEach(build::addModule)
    return List(BUILDS) {
      build.clearCommands()
      build.run(arrayOf(*tasks(*SEQUENTIAL_MODULES.toTypedArray()), "--max-workers=1", "--rerun-tasks"))
      build.commands()
    }
  }

  @Test
  fun maxSimulatorsPropertyCapsThePool() {
    val build = FixtureBuild(directory)
    build.addModule("second")

    val result = build.build(
      *tasks("lib", "second"),
      *parallel,
      "-Psimsteward.maxSimulators=1",
      "-Pfixture.holders=1",
      "-Pfixture.awaitWaiter",
    )

    assertEquals(1, build.creates().size, build.commands().toString())
    assertTrue("its pool is at its cap of 1 simulator" in result.output, result.output)
  }

  @Test
  fun invalidMaxSimulatorsFailsWithClearMessage() {
    val build = FixtureBuild(directory)

    val zero = build.buildAndFail(":lib:iosSimulatorArm64Test", "-Psimsteward.maxSimulators=0")
    val word = build.buildAndFail(":lib:iosSimulatorArm64Test", "-Psimsteward.maxSimulators=two")

    assertTrue("maxSimulators must be at least 1, but was 0." in zero.output, zero.output)
    assertTrue("simsteward.maxSimulators must be a whole number, but was 'two'." in word.output, word.output)
    assertEquals(emptyList(), build.creates())
  }

  @Test
  fun extensionMaxSimulatorsOverridesTheProperty() {
    val build = FixtureBuild(directory)
    build.addModule("second")
    build.simsteward("lib", "maxSimulators.set(1)")
    build.simsteward("second", "maxSimulators.set(1)")

    val result = build.build(*tasks("lib", "second"), *parallel, "-Pfixture.holders=1", "-Pfixture.awaitWaiter")

    assertEquals(1, build.creates().size, build.commands().toString())
    assertTrue("its pool is at its cap of 1 simulator" in result.output, result.output)
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
  fun firstLeaseRejectsNameThatAppearsDuringCreationAfterReaping() {
    val build = FixtureBuild(directory)
    val name = taskName(build, ":lib:iosSimulatorArm64Test")
    build.root.resolve("collision-on-create").writeText("== Devices ==\n    $name ($OTHER_UDID) (Shutdown)\n")
    val result = build.buildAndFail(":lib:iosSimulatorArm64Test", "-Psimsteward.slim=false")
    val commands = build.commands()
    assertTrue("Another simulator, $OTHER_UDID, is already named $name" in result.output, result.output)
    assertEquals(2, commands.count { it == "xcrun simctl list devices" })
    assertTrue(commands.indexOfFirst { "create" in it } in 1 until commands.indexOfLast { it == "xcrun simctl list devices" }, "$commands")
    assertTrue(commands.none { it == "test :lib:iosSimulatorArm64Test" || it.endsWith(" $name") }, "$commands")
    assertEquals("xcrun simctl delete ${FixtureBuild.UDID}", commands.last())
  }

  @Test
  fun laterLeaseRejectsNameThatAppearsAfterPreviousTaskReturns() {
    val build = FixtureBuild(directory)
    build.addModule("second")
    val name = taskName(build, ":second:iosSimulatorArm64Test")
    build.appendToLib(
      """
      tasks.named("iosSimulatorArm64Test") {
        doLast {
          File("${build.root.resolve("devices.txt")}").writeText("== Devices ==\n    $name ($OTHER_UDID) (Shutdown)\n")
        }
      }
      """.trimIndent(),
    )
    build.appendTo("second", "tasks.named(\"iosSimulatorArm64Test\") { mustRunAfter(\":lib:iosSimulatorArm64Test\") }")
    val result = build.buildAndFail(*tasks("lib", "second"), "--max-workers=1")
    val commands = build.commands()
    assertTrue("Another simulator, $OTHER_UDID, is already named $name" in result.output, result.output)
    assertEquals(1, build.creates().size, "$commands")
    assertEquals(3, commands.count { it == "xcrun simctl list devices" })
    assertTrue(commands.indexOf("test :lib:iosSimulatorArm64Test") in 0 until commands.indexOfLast { it == "xcrun simctl list devices" }, "$commands")
    assertTrue(commands.none { it == "test :second:iosSimulatorArm64Test" || it.endsWith(" $name") }, "$commands")
    assertEquals("xcrun simctl delete ${FixtureBuild.UDID}", commands.last())
  }

  private fun taskName(build: FixtureBuild, path: String): String =
    "simsteward-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(build.root.canonicalPath.toByteArray())).take(8) + path

  @Test
  fun failedTaskReturnsItsLeaseToAWaitingTask() {
    val build = FixtureBuild(directory)
    build.addModule("second")

    val result = build.buildAndFail(
      *tasks("lib", "second"),
      *parallel,
      "--continue",
      "-Psimsteward.maxSimulators=1",
      "-Pfixture.holders=1",
      "-Pfixture.awaitWaiter",
      "-Pfixture.failTest",
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
    const val BUILDS = 3
    val SEQUENTIAL_MODULES = listOf("lib", "second", "third", "fourth", "fifth", "sixth")
  }

  private fun tasks(vararg modules: String) = modules.map { ":$it:iosSimulatorArm64Test" }.toTypedArray()

  /** UDID to device name, for each rename to a task's name. */
  private fun FixtureBuild.leaseRenames(): Map<String, String> = commands()
    .mapNotNull { Regex("xcrun simctl rename (\\S+) (\\S+:\\S+)").find(it)?.destructured }
    .associate { (udid, device) -> udid to device }

  private fun BuildResult.device(module: String): String =
    Regex("fixture: :$module:iosSimulatorArm64Test device=(\\S+)").find(output)?.groupValues?.get(1)
      ?: error("No fixture line for $module in output:\n$output")
}
