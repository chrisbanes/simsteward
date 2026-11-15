package dev.chrisbanes.simsteward

import java.io.File
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
    val record = plant("simsteward-alive001", udid = DEAD_UDID)
    build.listDevices("simsteward-alive001" to DEAD_UDID)

    val result = build.build(task)

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

  private fun record(name: String) = FixtureBuild.RECORDS_DIR.resolve("$name.properties")

  private fun ownStartTime() = ProcessHandle.current().info().startInstant().get().toEpochMilli()

  private fun deadProcess(): Pair<Long, Long> {
    val process = ProcessBuilder("/usr/bin/true").start()
    val handle = process.toHandle()
    val start = handle.info().startInstant().get().toEpochMilli()
    process.waitFor()
    return handle.pid() to start
  }

  private fun plant(
    name: String,
    udid: String?,
    pid: Long = ProcessHandle.current().pid(),
    startTime: Long = ownStartTime(),
  ): File {
    val file = record(name)
    writeRecord(
      file,
      listOfNotNull(
        "name" to name,
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
