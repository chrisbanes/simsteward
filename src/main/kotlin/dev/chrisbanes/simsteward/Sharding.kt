// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.TestReport
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithSimulatorTests
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

/** Shard [index] (1-based) of [count] for one simulator test task. */
internal data class Shard(val index: Int, val count: Int)

/**
 * Splits every simulator test task of this project into [count] shard tasks, from KGP test runs
 * `shard1`…`shardN` on the same debug test binary. The original task runs its shards instead of its own
 * tests, unless `--tests` filters it. Returns the shards by task name; empty when sharding is off.
 */
internal fun Project.configureShards(count: Int, configurationCache: Boolean): Map<String, Shard> {
  checkAtLeastOne("testShards", count)
  val targets = extensions.getByType(KotlinMultiplatformExtension::class.java).targets.withType(KotlinNativeTargetWithSimulatorTests::class.java).toList()
  if (count == 1 || targets.isEmpty()) return emptyMap()
  // Without the configuration cache, Gradle runs one project's tasks one at a time, so shards would only add overhead.
  if (!configurationCache) {
    logger.lifecycle("simsteward: testShards=$count ignored for $path; sharding needs the configuration cache (org.gradle.configuration-cache=true).")
    return emptyMap()
  }
  val shards = mutableMapOf<String, Shard>()
  targets.forEach { target ->
    val original = tasks.named("${target.name}Test", KotlinNativeSimulatorTest::class.java)
    val shardTasks = (1..count).map { index ->
      target.testRuns.create("shard$index") { it.setExecutionSourceFrom(target.binaries.getTest(NativeBuildType.DEBUG)) }
      val name = "${target.name}Shard${index}Test"
      shards[name] = Shard(index, count)
      tasks.named(name, KotlinNativeSimulatorTest::class.java).also { shard ->
        shard.configure {
          it.group = null
          // Shards stand in for the original, so they keep its switch, failure policy and exclusions. Its onlyIf
          // predicates have no public reader.
          val from = original.get()
          it.enabled = from.enabled
          it.ignoreFailures = from.ignoreFailures
          it.filter.setExcludePatterns(*from.filter.excludePatterns.toTypedArray())
        }
      }
    }
    val report = tasks.register("${target.name}TestShardReport", TestReport::class.java) { report ->
      report.description = "Combines the reports of ${original.name}'s shards."
      // Location only: the original doesn't produce this report, so the report must not depend on it.
      report.destinationDirectory.set(original.flatMap { it.reports.html.outputLocation.locationOnly })
      // Ordered after the shards rather than depending on them, so a failing shard still gets the report.
      report.mustRunAfter(shardTasks)
      report.testResults.from(shardTasks.map { shard -> shard.flatMap { it.binaryResultsDirectory.locationOnly } })
    }
    shardTasks.forEach { shard -> shard.configure { it.finalizedBy(report) } }
    original.configure { task ->
      // Gradle applies --tests before it resolves dependencies, so a filtered run never schedules the shards.
      task.dependsOn(providers.provider { if (commandLineIncludePatterns(task).isEmpty()) shardTasks else emptyList() })
      task.onlyIf("it has a --tests filter; otherwise its shards run its tests") {
        commandLineIncludePatterns(it as AbstractTestTask).isNotEmpty()
      }
    }
  }
  return shards
}

/**
 * Runs after the lease and before KGP's test action: lists the test binary's classes on the leased
 * simulator and narrows the shard's filter to its share, so KGP's test command runs only those classes.
 */
internal class ShardAction(
  private val leases: Provider<LeaseService>,
  private val xcrun: Provider<String>,
  private val shard: Shard,
) : Action<Task> {
  override fun execute(task: Task) {
    task as KotlinNativeSimulatorTest
    // Clear a previous run's results first, so a shard that runs nothing doesn't show them in the combined report.
    task.binaryResultsDirectory.get().asFile.deleteRecursively()
    task.reports.junitXml.outputLocation.get().asFile.deleteRecursively()
    // Ending early, by failure or StopExecutionException, leaves the lease to the task-completion backstop.
    val listing = leases.get().listTests(xcrun.get(), task.device.get(), task.executable.absolutePath)
    val share = shareOf(listing, shard.index, shard.count)
    if (share.isEmpty()) {
      task.logger.lifecycle("simsteward: ${task.path} has no test classes in its share; skipping its test run.")
      throw StopExecutionException()
    }
    task.filter.setIncludePatterns(*share.toTypedArray())
  }
}

/**
 * The classes for shard [index] of [count] from `--ktest_list_tests` output, where each class is a
 * line `pkg.Class.` followed by its indented tests: sorted class names dealt in turn.
 */
internal fun shareOf(listing: String, index: Int, count: Int): List<String> =
  listing.lines()
    .filter { it.endsWith(".") && !it.first().isWhitespace() }
    .map { it.dropLast(1) }
    .distinct()
    .sorted()
    .filterIndexed { i, _ -> i % count == index - 1 }

/** The original task's `--tests` patterns, which Gradle's public test filter API does not expose. */
private fun commandLineIncludePatterns(task: AbstractTestTask): Set<String> =
  (task.filter as? DefaultTestFilter)?.commandLineIncludePatterns.orEmpty()
