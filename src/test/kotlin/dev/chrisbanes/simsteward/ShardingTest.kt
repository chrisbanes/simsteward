// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class ShardingTest {
  @TempDir
  lateinit var directory: File

  private val original = ":lib:iosSimulatorArm64Test"
  private val report = ":lib:iosSimulatorArm64TestShardReport"
  private fun shard(index: Int) = ":lib:iosSimulatorArm64Shard${index}Test"

  private fun sharded(count: Int, vararg classes: String = arrayOf("a.A", "b.B", "c.C")) = FixtureBuild(directory).apply {
    simsteward("lib", "testShards.set($count)")
    listTestClasses(*classes)
  }

  private fun FixtureBuild.filters(): Map<String, String> = commands().filter { it.startsWith("shard-filter ") }
    .associate { line -> line.removePrefix("shard-filter ").split(" ", limit = 2).let { it[0] to it[1] } }

  /** Asserts [leased] shard leases were taken (any, when null) and every one was renamed back to its pool name. */
  private fun FixtureBuild.assertShardLeasesReturned(leased: Int? = null) {
    val renames = commands().filter { it.startsWith("xcrun simctl rename ") }.partition { "Shard" in it }
    if (leased == null) assertTrue(renames.first.isNotEmpty(), commands().toString()) else assertEquals(leased, renames.first.size, commands().toString())
    assertEquals(renames.first.size, renames.second.size, commands().toString())
  }
  private fun BuildResult.outcome(path: String) = task(path)?.outcome

  @Test
  fun defaultCreatesNoShards() {
    val build = FixtureBuild(directory)
    build.listTestClasses("a.A")

    val tasks = build.build(":lib:tasks", "--all")
    assertFalse("Shard" in tasks.output, tasks.output)
    val result = build.build(original)

    assertEquals(TaskOutcome.SUCCESS, result.outcome(original))
    assertTrue(build.commands().none { it.startsWith("xcrun simctl spawn") }, build.commands().toString())
  }

  @Test
  fun threeShardsRunInsteadOfTheOriginal() {
    val build = sharded(3)

    val result = build.build(original)

    (1..3).forEach { assertEquals(TaskOutcome.SUCCESS, result.outcome(shard(it)), result.output) }
    assertEquals(TaskOutcome.SKIPPED, result.outcome(original))
    val devices = Regex("device=(\\S+)").findAll(result.output).map { it.groupValues[1] }.toSet()
    assertEquals((1..3).map { "Shard${it}Test" }.toSet(), devices.map { it.substringAfterLast("iosSimulatorArm64") }.toSet(), devices.toString())

    val grouped = build.build(":lib:tasks").output
    assertFalse("Shard1Test" in grouped, grouped)
    assertTrue("iosSimulatorArm64Shard1Test" in build.build(":lib:tasks", "--all").output)
  }

  @Test
  fun gradlePropertyShardsAndExtensionOverridesIt() {
    val build = FixtureBuild(directory)
    build.listTestClasses("a.A", "b.B")
    val property = build.build(original, "-Psimsteward.testShards=2")
    assertEquals(TaskOutcome.SUCCESS, property.outcome(shard(2)))
    assertNull(property.task(shard(3)))

    val off = FixtureBuild(directory.resolve("off"))
    off.simsteward("lib", "testShards.set(1)")
    val overridden = off.build(original, "-Psimsteward.testShards=2")
    assertNull(overridden.task(shard(1)))
    assertEquals(TaskOutcome.SUCCESS, overridden.outcome(original))
  }

  @Test
  fun invalidTestShardsFailWithClearMessages() {
    val build = FixtureBuild(directory)
    assertTrue(
      "simsteward.testShards must be a whole number, but was 'x'." in build.buildAndFail(original, "-Psimsteward.testShards=x").output,
    )
    assertTrue("testShards must be at least 1, but was 0." in build.buildAndFail(original, "-Psimsteward.testShards=0").output)
    build.simsteward("lib", "testShards.set(-1)")
    assertTrue("testShards must be at least 1, but was -1." in build.buildAndFail(original).output)
  }

  @Test
  fun withoutConfigurationCacheTheOriginalRunsAndSaysWhy() {
    val build = sharded(3)

    // A dry run, which lists what would run: without the configuration cache, newer KGP reads swift-import
    // outputs the fixture's stubs don't produce. Unsharded runs themselves are covered throughout the suite.
    val result = build.build(original, "--no-configuration-cache", "--dry-run")

    assertTrue("$original SKIPPED" in result.output, result.output) // Scheduled: dry runs report every task as SKIPPED.
    assertFalse("Shard1Test" in result.output, result.output)
    assertEquals(1, Regex("testShards=3 ignored for :lib; sharding needs the configuration cache").findAll(result.output).count(), result.output)
    assertTrue(build.commands().none { it.startsWith("xcrun simctl spawn") })
  }

  @Test
  fun filteredOriginalRunsAloneIncludingOnACacheHit() {
    val build = sharded(3)

    listOf(build.build(original, "--tests", "a.A"), build.build(original, "--tests", "a.A")).forEachIndexed { run, result ->
      if (run == 1) assertTrue("Reusing configuration cache." in result.output, result.output)
      (1..3).forEach { assertNull(result.task(shard(it)), result.output) }
      assertNull(result.task(report))
      assertTrue(result.outcome(original) in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE), result.output)
    }
    assertTrue(build.commands().none { it.startsWith("xcrun simctl spawn") }, build.commands().toString())

    val unfiltered = build.build(original)
    assertEquals(TaskOutcome.SUCCESS, unfiltered.outcome(shard(1)))
    assertEquals(TaskOutcome.SKIPPED, unfiltered.outcome(original))
  }

  @Test
  fun shardsOfOneProjectRunInParallel() {
    val build = sharded(3)

    // The barrier only opens once three tasks hold a lease at the same time.
    build.build(original, "-Psimsteward.maxSimulators=3", "-Pfixture.holders=3")

    assertEquals(3, build.creates().size, build.commands().toString())
  }

  @Test
  fun allTestsAndCheckRunEachShardOnce() {
    listOf(":lib:allTests", ":lib:check").forEach { entry ->
      val build = FixtureBuild(directory.resolve(entry.substringAfterLast(':')))
      build.simsteward("lib", "testShards.set(2)")
      build.listTestClasses("a.A", "b.B")

      val result = build.build(entry)

      (1..2).forEach { index -> assertEquals(1, build.commands().count { it == "test ${shard(index)}" }, build.commands().toString()) }
      assertEquals(TaskOutcome.SKIPPED, result.outcome(original))
    }
  }

  @Test
  fun disabledProjectDoesNotShard() {
    val build = sharded(3)
    build.simsteward("lib", "enabled.set(false)")

    val result = build.build(original)

    assertNull(result.task(shard(1)))
    assertEquals(TaskOutcome.SUCCESS, result.outcome(original))
  }

  @Test
  fun sharesCoverEveryClassOnceInSortedRoundRobin() {
    val build = sharded(3, "e.E", "a.A", "d.D", "b.B", "c.C")

    build.build(original)

    assertEquals(
      mapOf(shard(1) to "[a.A, d.D]", shard(2) to "[b.B, e.E]", shard(3) to "[c.C]"),
      build.filters(),
    )
    (1..3).forEach { index ->
      val spawn = build.commands().single { it.startsWith("xcrun simctl spawn ") && it.contains("Shard${index}Test ") }
      assertTrue(spawn.endsWith("lib/test.kexe --ktest_list_tests"), spawn)
    }
  }

  @Test
  fun shardsWithoutClassesPass() {
    val build = sharded(3, "a.A", "b.B")

    val result = build.build(original)

    assertEquals(TaskOutcome.SUCCESS, result.outcome(shard(3)))
    assertFalse("test ${shard(3)}" in build.commands(), build.commands().toString())
    build.assertShardLeasesReturned(3)
  }

  @Test
  fun listingFailureFailsTheShardAndReturnsItsLease() {
    val build = sharded(2)
    build.failOn("simctl spawn")

    val result = build.buildAndFail(original, "--max-workers=1")

    assertTrue("Simulator command failed with exit code 3" in result.output, result.output)
    assertTrue("xcrun: injected failure for simctl spawn" in result.output, result.output)
    build.assertShardLeasesReturned()
    assertTrue(build.commands().none { it.startsWith("test ") }, build.commands().toString())
  }

  @Test
  fun shardsBeyondTheCapWaitForAReturnedLease() {
    val build = sharded(3)
    val shards = (1..3).map(::shard).toTypedArray()

    // Requesting the shards by path lets the fixture's barrier see the waiting shard.
    val result = build.build(*shards, "-Pfixture.holders=2", "-Pfixture.awaitWaiter")

    assertEquals(2, build.creates().size, build.commands().toString())
    assertEquals(3, build.commands().count { it.startsWith("test ") }, build.commands().toString())
    assertTrue(
      Regex("simsteward: :lib:iosSimulatorArm64Shard\\dTest is waiting for a returned lease").containsMatchIn(result.output),
      result.output,
    )
  }

  @Test
  fun failingShardsReturnTheirLeasesAndStillGetTheCombinedReport() {
    val build = sharded(2)

    val result = build.buildAndFail(original, "-Pfixture.failTest")

    build.assertShardLeasesReturned()
    assertTrue(result.task(report) != null, result.output)
  }

  @Test
  fun secondIdenticalRunIsUpToDateWithoutLeases() {
    val build = sharded(2)
    build.build(original)
    build.clearCommands()

    val again = build.build(original)

    (1..2).forEach { assertEquals(TaskOutcome.UP_TO_DATE, again.outcome(shard(it)), again.output) }
    assertEquals(emptyList(), build.commands())
  }

  @Test
  fun changingTheShardCountRerunsEveryShard() {
    // The property, because the extension would override it.
    val build = FixtureBuild(directory)
    build.listTestClasses("a.A", "b.B", "c.C", "d.D", "e.E")
    build.build(original, "-Psimsteward.testShards=3")
    build.clearCommands()

    val result = build.build(original, "-Psimsteward.testShards=2")

    (1..2).forEach { assertEquals(TaskOutcome.SUCCESS, result.outcome(shard(it)), result.output) }
    assertEquals(mapOf(shard(1) to "[a.A, c.C, e.E]", shard(2) to "[b.B, d.D]"), build.filters())
  }

  @Test
  fun unpinnedShardsRerunOnANewRuntime() {
    val build = FixtureBuild(directory, pinned = false)
    build.simsteward("lib", "testShards.set(2)")
    build.listTestClasses("a.A", "b.B")
    build.build(original)
    assertEquals(TaskOutcome.UP_TO_DATE, build.build(original).outcome(shard(1)))

    build.installRuntimes(FixtureBuild.runtimesJson(FixtureBuild.runtime("iOS", "28.0", "iPhone", "iPhone 18")))
    build.clearCommands()
    val result = build.build(original)

    (1..2).forEach { assertEquals(TaskOutcome.SUCCESS, result.outcome(shard(it))) }
    assertTrue(build.creates().all { "iOS-28-0" in it }, build.creates().toString())
  }

  @Test
  fun slimShardsSlimTheirSimulators() {
    val build = sharded(2)

    build.build(original, "-Psimsteward.slim=true")

    val creates = build.creates().size
    assertTrue(creates > 0)
    assertEquals(creates, build.commands().count { it.startsWith("simslim on ") }, build.commands().toString())
  }

  @Test
  fun combinedReportFinalizesShardsInTheOriginalsReportFolder() {
    val build = sharded(2)
    build.appendTo(
      "lib",
      """
      afterEvaluate {
        val report = tasks.named<org.gradle.api.tasks.testing.TestReport>("iosSimulatorArm64TestShardReport").get()
        check(report.destinationDirectory.get().asFile == layout.buildDirectory.dir("reports/tests/iosSimulatorArm64Test").get().asFile) {
          "report writes to ${'$'}{report.destinationDirectory.get()}"
        }
      }
      """,
    )

    val result = build.build(original)

    val order = result.tasks.map { it.path }
    assertTrue(order.indexOf(report) > (1..2).maxOf { order.indexOf(shard(it)) }, order.toString())
  }
}
