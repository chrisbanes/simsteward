// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.gradle.testfixtures.ProjectBuilder

class OwnerIdentityTest {
  @Test
  fun ownerDecisionRequiresIndependentUsableEvidence() {
    val start = 1_700_000_000_123L
    val seconds = start / 1000
    fun decision(java: Long?, ps: Long?, recorded: Long? = seconds, alive: Boolean = true) =
      ownerIsAlive(start, recorded) { OwnerProcess(alive, java, ps) }

    assertEquals(true, decision(start, null))
    assertEquals(false, decision(start + 1, seconds))
    assertEquals(null, decision(0, seconds))
    assertEquals(null, ownerIsAlive(0, seconds) { OwnerProcess(false, null, null) })
    assertEquals(false, decision(null, null, alive = false))
    assertEquals(true, decision(null, seconds))
    assertEquals(false, decision(null, seconds + 1))
    assertEquals(null, decision(null, seconds + 1, recorded = null), "Legacy records retain an unknown owner")
    assertEquals(null, decision(null, null))
    assertEquals(null, decision(null, -1))
    assertEquals(null, decision(null, seconds + 1, recorded = seconds - 1), "Inconsistent record is not evidence")
    assertEquals(null, ownerIsAlive(start, seconds) { error("permission denied") })
    assertEquals(true, decision(null, seconds), "Same-second ambiguity must preserve the owner")
  }

  @Test
  fun psTimestampParserRejectsIncompleteInvalidOrMultipleObservations() {
    val expected = 1_700_000_000L // Tue Nov 14 22:13:20 2023 UTC
    assertEquals(expected, parseOwnerStartSeconds("Tue Nov 14 22:13:20 2023\n"))
    assertNotNull(parseOwnerStartSeconds("  Wed Nov  1 22:13:20 2023\n"))
    listOf("", "Tue Nov 14 22:13:20", "Tue Nov 14 22:13:20 2023\n\n", "Tue Nov 14 22:13:20 2023\nother", "Thu Feb 30 22:13:20 2023", "Mon Nov 14 22:13:20 2023", "Tue Nov 14 25:13:20 2023", "Tue Nov 14 22:13:20 0000").forEach {
      assertEquals(null, parseOwnerStartSeconds(it), it)
    }
  }

  @Test
  fun actualReaderMatchesJavaBirthSecondsDespiteCallerLocaleAndTimezone() {
    val project = ProjectBuilder.builder().build()
    val service = project.gradle.sharedServices.registerIfAbsent("identity", LeaseService::class.java) {}.get()
    val locale = Locale.getDefault()
    val zone = TimeZone.getDefault()
    try {
      Locale.setDefault(Locale.FRANCE)
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
      val owner = ProcessHandle.current()
      repeat(3) {
        assertEquals(owner.info().startInstant().get().epochSecond, readOwnerStartSeconds(service.exec, owner.pid()))
      }
      val child = ProcessBuilder("/bin/sleep", "5").start()
      try {
        assertEquals(child.toHandle().info().startInstant().get().epochSecond, readOwnerStartSeconds(service.exec, child.pid()))
      } finally {
        child.destroy()
        child.waitFor()
      }
      assertEquals(null, readOwnerStartSeconds(service.exec, child.pid()))
      assertEquals(null, readOwnerStartSeconds(service.exec, 0))
    } finally {
      Locale.setDefault(locale)
      TimeZone.setDefault(zone)
      service.close()
    }
  }
}
