// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ReapingTest {
  @TempDir
  lateinit var directory: File

  private val task = ":lib:iosSimulatorArm64Test"
  private val planted = mutableListOf<File>()

  @AfterTest
  fun removePlantedRecords() = planted.forEach { it.delete() }

  @Test
  fun recordExistsWhileBuildOwnsSimulatorAndIsRemovedAfterDeletion() {
    val build = FixtureBuild(directory)
    // Stock, so the fake xcrun's bootstatus runs and snapshots the record after create.
    build.build(task, "-Psimsteward.slim=false")

    val pool = build.poolName()
    val atCreate = readRecord(build.root.resolve("record-at-create.properties"))
    val afterCreate = readRecord(build.root.resolve("record-after-create.properties"))
    assertEquals(pool, atCreate["name"])
    assertEquals(build.root.canonicalPath, File(atCreate["buildRoot"]!!).canonicalPath)
    assertTrue(atCreate["pid"]!!.toLong() > 0 && atCreate["startTime"]!!.toLong() > 0)
    assertEquals(null, atCreate["udid"], "UDID is not known before create")
    assertEquals(FixtureBuild.UDID, afterCreate["udid"])
    assertEquals(build.root.resolve("bin/xcrun").absolutePath, atCreate["xcrun"])
    assertEquals(atCreate["xcrun"], afterCreate["xcrun"])
    assertEquals(atCreate["ownerStartTimeSeconds"], afterCreate["ownerStartTimeSeconds"])
    assertEquals(atCreate.getValue("startTime").toLong() / 1000, atCreate["ownerStartTimeSeconds"]?.toLong())
    assertFalse(record(pool).exists())
  }

  @Test
  fun recordWithDeadOwnerIsReapedBeforeTheFirstLease() {
    val build = FixtureBuild(directory)
    val (pid, start) = deadProcess()
    val record = plant("simsteward-dead0001", udid = DEAD_UDID, pid = pid, startTime = start)
    // A lease renames the simulator, so the listing may show a task name rather than the pool name.
    build.listDevices("simsteward-abcd1234:lib:iosSimulatorArm64Test" to DEAD_UDID)

    build.build(task)

    val commands = build.commands()
    assertTrue(commands.indexOf("xcrun simctl delete $DEAD_UDID") in 0 until commands.indexOfFirst { " create " in it }, "$commands")
    assertFalse(record.exists())
  }

  @Test
  fun recordWithReusedPidIsReaped() {
    val build = FixtureBuild(directory)
    val record = plant("simsteward-reuse001", udid = DEAD_UDID, startTime = ownStartTime() + 1000)
    build.listDevices("simsteward-reuse001" to DEAD_UDID)

    build.build(task)

    assertTrue("xcrun simctl delete $DEAD_UDID" in build.commands())
    assertFalse(record.exists())
  }

  @Test
  fun recordWithLiveOwnerIsLeftAlone() {
    val build = FixtureBuild(directory)
    build.addModule("second")
    val record = plant("simsteward-alive001", udid = DEAD_UDID)
    build.listDevices("simsteward-alive001" to DEAD_UDID)

    val result = build.build(task, ":second:iosSimulatorArm64Test", "--parallel", "--max-workers=4", "-Pfixture.holders=2")

    assertFalse("xcrun simctl delete $DEAD_UDID" in build.commands())
    assertTrue(record.exists())
    assertFalse("alive001" in result.output, "A live owner's simulator is not an unrecorded one:\n${result.output}")
  }

  @Test
  fun recordWithoutUdidIsReapedByItsRecordedName() {
    val build = FixtureBuild(directory)
    val (pid, start) = deadProcess()
    val record = plant("simsteward-noudid01", udid = null, pid = pid, startTime = start)
    build.listDevices("simsteward-noudid01" to DEAD_UDID, "simsteward-other001" to OTHER_UDID)

    build.build(task)

    assertTrue("xcrun simctl delete $DEAD_UDID" in build.commands())
    assertFalse("xcrun simctl delete $OTHER_UDID" in build.commands())
    assertFalse(record.exists())
  }

  @Test
  fun deadRecordWhoseSimulatorIsGoneIsRemovedWithoutDeleting() {
    val build = FixtureBuild(directory)
    val (pid, start) = deadProcess()
    val record = plant("simsteward-gone0001", udid = DEAD_UDID, pid = pid, startTime = start)
    build.listDevices()

    build.build(task)

    assertFalse(build.commands().any { "simctl delete $DEAD_UDID" in it })
    assertFalse(record.exists())
  }

  @Test
  fun prefixedSimulatorWithoutRecordIsReportedAndNeverDeleted() {
    val build = FixtureBuild(directory)
    build.listDevices("simsteward-norecord1" to DEAD_UDID, "iPhone 17" to OTHER_UDID)

    val result = build.build(task)

    assertTrue("simsteward-norecord1" in result.output && DEAD_UDID in result.output, result.output)
    assertFalse(OTHER_UDID in result.output)
    assertFalse(build.commands().any { "simctl delete $DEAD_UDID" in it || "simctl delete $OTHER_UDID" in it })
  }

  @Test
  fun cleanupTaskReapsWithoutRunningTests() {
    val build = FixtureBuild(directory)
    val (pid, start) = deadProcess()
    val record = plant("simsteward-manual01", udid = DEAD_UDID, pid = pid, startTime = start)
    build.listDevices("simsteward-manual01" to DEAD_UDID)

    build.build(":lib:simstewardReap")

    assertEquals(listOf("xcrun simctl list devices", "xcrun simctl delete $DEAD_UDID"), build.commands())
    assertFalse(record.exists())
  }

  @Test
  fun twoProjectExecutablesReapTheirOwnRecordsBeforeTheirFirstCreate() {
    val build = FixtureBuild(directory)
    build.addModule("second")
    val firstXcrun = build.installXcrun("xcrun-first", "simsteward-routeone" to DEAD_UDID)
    val secondXcrun = build.installXcrun("xcrun-second", "simsteward-routetwo" to OTHER_UDID)
    build.localXcrun("lib", firstXcrun)
    build.localXcrun("second", secondXcrun)
    val (pid, start) = deadProcess()
    val one = plant("simsteward-routeone", DEAD_UDID, pid, start, firstXcrun)
    val two = plant("simsteward-routetwo", OTHER_UDID, pid, start, secondXcrun)
    // The first route's listing lacks the other orphan: it must retain that record for route two.
    val args = arrayOf(task, ":second:iosSimulatorArm64Test", "-Psimsteward.slim=false", "--max-workers=1", "-Dorg.gradle.unsafe.isolated-projects=true", "--rerun-tasks")
    build.build(*args)

    val commands = build.commands()
    for ((label, udid) in listOf("xcrun-first" to DEAD_UDID, "xcrun-second" to OTHER_UDID)) {
      assertTrue(commands.indexOf("$label simctl delete $udid") in 0 until commands.indexOfFirst { it.startsWith("$label simctl create ") }, "$commands")
      val snapshot = readRecord(build.root.resolve("record-at-create-$label.properties"))
      assertEquals(build.root.resolve("bin/$label").absolutePath, snapshot["xcrun"])
      assertEquals(snapshot["xcrun"], readRecord(build.root.resolve("record-after-create-$label.properties"))["xcrun"])
    }
    assertFalse(commands.contains("xcrun-first simctl delete $OTHER_UDID"))
    assertFalse(commands.contains("xcrun-second simctl delete $DEAD_UDID"))
    assertFalse(one.exists())
    assertFalse(two.exists())
    build.clearCommands()
    val again = build.build(*args)
    assertTrue("Reusing configuration cache." in again.output, again.output)
    for (label in listOf("xcrun-first", "xcrun-second")) {
      assertEquals(1, build.commands().count { it.startsWith("$label simctl create ") })
    }
  }

  @Test
  fun failedCreateLeavesPreCreateProvenanceAndFailedDeleteCanBeRetriedManually() {
    val build = FixtureBuild(directory)
    build.failOn("simctl create")
    build.buildAndFail(task)
    val snapshot = readRecord(build.root.resolve("record-at-create.properties"))
    val trace = record(snapshot.getValue("name")).also(planted::add)
    assertEquals(build.root.resolve("bin/xcrun").absolutePath, snapshot["xcrun"])
    assertEquals(null, snapshot["udid"])
    assertTrue(trace.exists())

    val (pid, start) = deadProcess()
    val orphan = plant("simsteward-retrydel", DEAD_UDID, pid, start)
    build.listDevices("simsteward-retrydel" to DEAD_UDID)
    build.failOn("simctl delete")
    build.build(":lib:simstewardReap")
    assertTrue(orphan.exists())
    build.root.resolve("fail-on").delete()
    build.build(":lib:simstewardReap")
    assertFalse(orphan.exists())
  }

  @Test
  fun malformedRequiredIdentityIsRetainedAndInitialListFailureDoesNotCreate() {
    val build = FixtureBuild(directory)
    val record = plant("simsteward-invalid1", DEAD_UDID, pid = -1, startTime = 0)
    build.listDevices("simsteward-invalid1" to DEAD_UDID)
    build.failOn("simctl list devices")
    build.buildAndFail(task)
    assertTrue(record.exists())
    assertTrue(build.creates().isEmpty())
    build.root.resolve("fail-on").delete()
    val result = build.build(task)
    assertTrue(record.exists())
    assertTrue("Ignoring unreadable simsteward record $record" in result.output)
    assertFalse("xcrun simctl delete $DEAD_UDID" in build.commands())
  }

  @Test
  fun laterLeaseOfSamePairLeavesNewOrphanUntilManualReap() {
    val build = FixtureBuild(directory)
    build.addModule("second")
    val (pid, start) = deadProcess()
    val late = record("simsteward-latepair").also(planted::add)
    build.listDevices("simsteward-latepair" to DEAD_UDID)
    build.appendToLib(
      """
      tasks.named("iosSimulatorArm64Test") {
        doLast {
          File("$late").writeText("name=simsteward-latepair\npid=$pid\nstartTime=$start\nudid=$DEAD_UDID\n")
        }
      }
      """.trimIndent(),
    )
    build.appendTo("second", "tasks.named(\"iosSimulatorArm64Test\") { mustRunAfter(\":lib:iosSimulatorArm64Test\") }")
    build.build(task, ":second:iosSimulatorArm64Test", "--max-workers=1")
    assertTrue(late.exists(), "A later lease must not automatically reap the same pair again")
    assertFalse("xcrun simctl delete $DEAD_UDID" in build.commands())
    build.build(":lib:simstewardReap")
    assertFalse(late.exists())
    assertTrue("xcrun simctl delete $DEAD_UDID" in build.commands())
  }

  @Test
  fun anotherBuildCannotReapAnActivelyHeldLease() {
    val holder = FixtureBuild(directory.resolve("holder"))
    val reaper = FixtureBuild(directory.resolve("reaper"))
    val pending = CompletableFuture.supplyAsync { holder.build(task, "-Pfixture.holdLease", "-Psimsteward.slim=false") }
    try {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
      while (!holder.root.resolve("lease-ready").exists() && !pending.isDone) {
        check(System.nanoTime() < deadline) { "Holder did not reach the lease barrier" }
        Thread.sleep(10)
      }
      assertFalse(pending.isDone, "Lease holder must still be running")
      val liveRecord = record(holder.poolName())
      assertTrue(liveRecord.exists())
      holder.listDevices(holder.poolName() to FixtureBuild.UDID)
      // Same configured route, so this tests liveness rather than provenance exclusion.
      reaper.build(":lib:simstewardReap", "-Psimsteward.xcrun=${holder.root.resolve("bin/xcrun")}")
      assertTrue(liveRecord.exists())
      assertFalse(holder.commands().any { it == "xcrun simctl delete ${FixtureBuild.UDID}" })
    } finally {
      holder.root.resolve("lease-release").writeText("release")
      pending.get(90, TimeUnit.SECONDS)
    }
    assertFalse(record(holder.poolName()).exists())
    assertEquals("xcrun simctl delete ${FixtureBuild.UDID}", holder.commands().last())
  }

  private fun record(name: String) = FixtureBuild.RECORDS_DIR.resolve("$name.properties")

  private fun ownStartTime() = ProcessHandle.current().info().startInstant().get().toEpochMilli()

  private fun deadProcess(): Pair<Long, Long> {
    val process = ProcessBuilder("/bin/sleep", "60").start()
    try {
      val handle = process.toHandle()
      return handle.pid() to handle.info().startInstant().get().toEpochMilli()
    } finally {
      process.destroy()
      process.waitFor()
    }
  }

  private fun plant(
    name: String,
    udid: String?,
    pid: Long = ProcessHandle.current().pid(),
    startTime: Long = ownStartTime(),
    xcrun: String? = null,
  ): File {
    val file = record(name)
    writeRecord(
      file,
      listOfNotNull(
        "name" to name,
        xcrun?.let { "xcrun" to it },
        udid?.let { "udid" to it },
        "buildRoot" to "/planted",
        "pid" to pid.toString(),
        "startTime" to startTime.toString(),
      ).toMap(),
    )
    return file.also(planted::add)
  }

  private companion object {
    const val DEAD_UDID = "11111111-2222-3333-4444-555555555555"
    const val OTHER_UDID = "66666666-7777-8888-9999-000000000000"
  }
}
