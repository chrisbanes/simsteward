// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.Properties
import org.gradle.process.ExecOperations

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

/** Independently observed birth times for the process currently occupying a PID. */
internal data class OwnerProcess(val alive: Boolean, val startTime: Long?, val startSeconds: Long?)

/** Null means the observation could not establish which live process occupies the PID. */
internal fun ownerIsAlive(startTime: Long, startSeconds: Long?, observe: () -> OwnerProcess): Boolean? = runCatching {
  require(startTime > 0)
  val process = observe()
  when {
    !process.alive -> false
    process.startTime != null -> if (process.startTime > 0) process.startTime == startTime else null
    startSeconds != null && startSeconds > 0 && startSeconds == startTime / 1000 && process.startSeconds != null && process.startSeconds > 0 ->
      process.startSeconds == startSeconds
    else -> null
  }
}.getOrNull()

/** `ps` reads kernel birth metadata independently of Java's optional startInstant. */
internal fun readOwnerStartSeconds(exec: ExecOperations, pid: Long): Long? {
  if (pid <= 0) return null
  return runCatching {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
      it.commandLine("/bin/ps", "-p", pid.toString(), "-o", "lstart=")
      it.environment("LC_ALL", "C")
      it.environment("TZ", "UTC")
      it.standardOutput = output
      it.errorOutput = ByteArrayOutputStream()
      it.isIgnoreExitValue = true
    }
    if (result.exitValue == 0) parseOwnerStartSeconds(output.toString(Charsets.UTF_8)) else null
  }.getOrNull()
}

internal fun parseOwnerStartSeconds(output: String): Long? = runCatching {
  // Permit ps's day padding and a single terminating newline, never partial or multiple lines.
  val line = output.removeSuffix("\n").trim(' ')
  require(Regex("[A-Z][a-z]{2} +[A-Z][a-z]{2} +[0-9]{1,2} +[0-9]{2}:[0-9]{2}:[0-9]{2} +[0-9]{4}").matches(line))
  val format = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
  LocalDateTime.parse(line.replace(Regex(" +"), " "), format).toEpochSecond(ZoneOffset.UTC).takeIf { it > 0 }
}.getOrNull()
