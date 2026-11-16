// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class LeaseServiceTest {
  @TempDir lateinit var directory: File

  @Test
  fun waitingObservationIsExactAndClearedOnInterruption() {
    withService { service, spec ->
      val ownership = Ownership(directory.resolve("records"), "test")
      service.lease(":holder", "holder", spec, ownership, 1)
      val failure = AtomicReference<Throwable>()
      val waiter = Thread {
        try {
          service.lease(":waiter", "waiter", spec, ownership, 1)
        } catch (e: Throwable) {
          failure.set(e)
        }
      }
      try {
        assertFalse(service.isWaitingForLease(setOf(":waiter")))
        waiter.start()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!service.isWaitingForLease(setOf(":waiter")) && System.nanoTime() < deadline) Thread.yield()
        assertTrue(service.isWaitingForLease(setOf(":waiter")))
        assertFalse(service.isWaitingForLease(setOf(":other")))
        waiter.interrupt()
        waiter.join(10_000)
        assertFalse(waiter.isAlive)
        assertTrue(failure.get() is InterruptedException)
        assertFalse(service.isWaitingForLease(setOf(":waiter")))
      } finally {
        waiter.interrupt()
        waiter.join(10_000)
        service.release(":holder")
      }
    }
  }

  @Test
  fun startupIncludesRecordsDirectoryAndNormalizesEquivalentPathsButManualReapAlwaysRuns() {
    withService { service, spec ->
      val first = directory.resolve("records-one")
      val second = directory.resolve("records-two")
      val orphanOne = plant(first, "one")
      val orphanTwo = plant(second, "two")
      service.lease(":one", "task-one", spec, Ownership(first, "test"), 1)
      service.release(":one")
      assertFalse(orphanOne.exists())
      assertTrue(orphanTwo.exists())
      val late = plant(first, "late")
      service.lease(":two", "task-two", spec, Ownership(second, "test"), 1)
      service.release(":two")
      assertFalse(orphanTwo.exists())
      service.lease(":three", "task-three", spec, Ownership(first.resolve("../records-one"), "test"), 1)
      service.release(":three")
      assertTrue(late.exists(), "Equivalent pair must not reap twice")
      service.reap(spec.xcrun, first)
      assertFalse(late.exists(), "Manual reap remains explicit")
      assertEquals(3, directory.resolve("commands").readLines().count { it == "simctl delete $ORPHAN" })
    }
  }

  @Test
  fun failedInitialListingDoesNotMarkPairCompleteOrCreateAndRetryReaps() {
    withService { service, spec ->
      val records = directory.resolve("records")
      val orphan = plant(records, "retry")
      val failure = directory.resolve("fail-list").apply { writeText("") }
      assertFails { service.lease(":one", "task-one", spec, Ownership(records, "test"), 1) }
      assertTrue(orphan.exists())
      assertEquals(listOf("simctl list devices"), directory.resolve("commands").readLines())
      failure.delete()
      service.lease(":two", "task-two", spec, Ownership(records, "test"), 1)
      assertFalse(orphan.exists())
      assertTrue(directory.resolve("commands").readLines().indexOf("simctl delete $ORPHAN") in 0 until directory.resolve("commands").readLines().indexOfFirst { "create" in it })
    }
  }

  @Test
  fun unknownIdentityDeletesOnlyWithValidIndependentBirthMismatch() {
    withService { service, spec ->
      val records = directory.resolve("records")
      val start = ProcessHandle.current().info().startInstant().get().toEpochMilli()
      val seconds = start / 1000
      val record = records.resolve("unknown.properties")
      val values = mapOf("name" to "simsteward-orphan", "pid" to ProcessHandle.current().pid().toString(), "startTime" to start.toString(), "udid" to ORPHAN)
      for (fallback in listOf(null, "invalid", "-1", (seconds - 1).toString(), seconds.toString())) {
        writeRecord(record, values + listOfNotNull(fallback?.let { "ownerStartTimeSeconds" to it }).toMap())
        service.reap(spec.xcrun, records) { _, _ -> OwnerProcess(true, null, if (fallback == seconds.toString()) seconds else seconds + 1) }
        assertTrue(record.exists(), "Missing, inconsistent or matching birth evidence must retain owner: $fallback")
      }
      service.reap(spec.xcrun, records) { _, _ -> error("denied") }
      assertTrue(record.exists())
      service.reap(spec.xcrun, records) { _, _ -> OwnerProcess(true, null, null) }
      assertTrue(record.exists())
      assertFalse(directory.resolve("commands").readLines().any { "delete" in it })
      service.reap(spec.xcrun, records) { _, _ -> OwnerProcess(true, null, seconds + 1) }
      assertFalse(record.exists())
      assertEquals("simctl delete $ORPHAN", directory.resolve("commands").readLines().last())
    }
  }

  private fun withService(action: (LeaseService, SimulatorSpec) -> Unit) {
    val project = ProjectBuilder.builder().withProjectDir(directory.resolve("project")).build()
    val service = project.gradle.sharedServices.registerIfAbsent("leases", LeaseService::class.java) {}.get()
    val fake = directory.resolve("xcrun").apply {
      writeText(
        """
        #!/bin/sh
        echo "${'$'}*" >> '${directory.resolve("commands")}'
        case "${'$'}1 ${'$'}2" in
          "simctl list")
            [ ! -f '${directory.resolve("fail-list")}' ] || exit 3
            echo '== Devices =='
            echo '    simsteward-orphan ($ORPHAN) (Shutdown)' ;;
          "simctl create") echo '${FixtureBuild.UDID}' ;;
        esac
        """.trimIndent(),
      )
      setExecutable(true)
    }
    try {
      action(service, SimulatorSpec(FixtureBuild.DEVICE_TYPE, FixtureBuild.RUNTIME, fake.absolutePath, Slimming(false, "unused", null)))
    } finally {
      service.close()
    }
  }

  private fun plant(records: File, name: String): File {
    val dead = ProcessBuilder("/usr/bin/true").start().apply { waitFor() }
    return records.resolve("$name.properties").also {
      writeRecord(it, mapOf("name" to "simsteward-orphan", "pid" to dead.pid().toString(), "startTime" to "1", "udid" to ORPHAN))
    }
  }

  private companion object {
    const val ORPHAN = "11111111-2222-3333-4444-555555555555"
  }
}
