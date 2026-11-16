// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import kotlin.test.Test
import kotlin.test.assertEquals

class ShardShareTest {
  private val listing = "b.B.\n  t1\n\na.A.\n  t2\n  t3\nc.C.\n  t4\n"

  @Test
  fun sortedClassesAreDealtInTurn() {
    assertEquals(listOf("a.A", "c.C"), shareOf(listing, 1, 2))
    assertEquals(listOf("b.B"), shareOf(listing, 2, 2))
  }

  @Test
  fun shardsBeyondTheClassCountAreEmpty() {
    assertEquals(emptyList(), shareOf(listing, 4, 4))
    assertEquals(emptyList(), shareOf("", 1, 2))
  }
}
