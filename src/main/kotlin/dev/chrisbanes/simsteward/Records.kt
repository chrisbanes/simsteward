// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties

/** Replaces [file] atomically, so a reaper never reads a half-written record. */
internal fun writeRecord(file: File, values: Map<String, String>) {
  file.parentFile.mkdirs()
  val properties = Properties().apply { values.forEach { (k, v) -> setProperty(k, v) } }
  // The `.tmp` suffix keeps it out of the reaper's `*.properties` listing.
  val temp = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
  try {
    Files.newBufferedWriter(temp).use { properties.store(it, null) }
    Files.move(temp, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
  } finally {
    Files.deleteIfExists(temp)
  }
}

internal fun readRecord(file: File): Map<String, String> = runCatching {
  Properties().apply { file.reader().use(::load) }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
}.getOrDefault(emptyMap())
