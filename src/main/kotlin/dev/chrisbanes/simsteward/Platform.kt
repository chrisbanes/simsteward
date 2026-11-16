// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.jetbrains.kotlin.konan.target.Family

/** The Apple simulator families simsteward manages, named as `simctl` names them. */
internal enum class Platform(val simctlName: String, val productFamily: String, val property: String) {
  IOS("iOS", "iPhone", "ios"),
  TVOS("tvOS", "Apple TV", "tvos"),
  WATCHOS("watchOS", "Apple Watch", "watchos"),
  ;

  companion object {
    fun of(family: Family): Platform? = when (family) {
      Family.IOS -> IOS
      Family.TVOS -> TVOS
      Family.WATCHOS -> WATCHOS
      else -> null
    }
  }
}

/**
 * Picks the newest installed runtime of [platform] and a device type it supports from the JSON of
 * `simctl list -j runtimes`, read unless both are pinned by identifier. A pinned [runtime] or [deviceType], given as an
 * identifier or a name, overrides the pick; the spec always holds identifiers.
 */
internal fun resolveSpec(
  platform: Platform,
  runtimesJson: () -> String,
  runtime: String?,
  deviceType: String?,
  xcrun: String,
  slimming: Slimming,
): SimulatorSpec {
  if (pinnedByIdentifiers(runtime, deviceType)) return SimulatorSpec(deviceType!!, runtime!!, xcrun, slimming)
  fun matches(entry: Map<*, *>, pin: String) = entry["identifier"] == pin || entry["name"] == pin
  val runtimes = ((JsonSlurper().parseText(runtimesJson()) as Map<*, *>)["runtimes"] as List<*>)
    .map { it as Map<*, *> }
    .filter { it["platform"] == platform.simctlName && it["isAvailable"] == true }
    .filter { runtime == null || matches(it, runtime) }
  val matched = runtimes.map { it["identifier"] as String }.distinct().sorted()
  if (runtime != null && matched.size > 1) {
    throw GradleException(
      "${platform.simctlName} runtime '$runtime' matches several installed runtimes: ${matched.joinToString()}. " +
        "Set simsteward.${platform.property}.runtime to one of these identifiers in gradle.properties.",
    )
  }
  fun supported(r: Map<*, *>) = (r["supportedDeviceTypes"] as List<*>).map { it as Map<*, *> }
    .filter { deviceType?.let { pinned -> matches(it, pinned) } ?: (it["productFamily"] == platform.productFamily) }
    .map { it["identifier"] as String }

  fun version(r: Map<*, *>): List<Int> {
    val raw = r["version"] as? String
    val parts = raw?.split('.')
    if (parts == null || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } || it.toIntOrNull() == null }) {
      throw GradleException("Invalid runtime version '$raw' for '${r["identifier"]}'; expected dotted numeric components.")
    }
    return parts.map { it.toInt() }
  }
  val newestFirst = Comparator<List<Int>> { a, b ->
    a.zip(b).map { (x, y) -> y.compareTo(x) }.firstOrNull { it != 0 } ?: b.size.compareTo(a.size)
  }
  val eligible = runtimes.filter { supported(it).isNotEmpty() }
  val candidates = if (runtime != null) {
    eligible
  } else {
    val ordered = eligible.map { it to version(it) }.sortedWith(compareBy(newestFirst) { it.second })
    val greatest = ordered.firstOrNull()?.second
    val top = ordered.takeWhile { greatest != null && newestFirst.compare(it.second, greatest) == 0 }
    val byId = top.groupBy { it.first["identifier"] as String }
    if (byId.size > 1) {
      val ids = byId.keys.sorted()
      val details = ids.joinToString("; ") { id ->
        "$id: " + byId.getValue(id).map { (it.first["buildversion"] as? String) ?: "not reported" }.distinct().sorted().joinToString(", ")
      }
      throw GradleException(
        "Multiple ${platform.simctlName} runtime identifiers share greatest version ${greatest!!.joinToString(".")}: " +
          "$details. Set simsteward.${platform.property}.runtime=${ids.first()} in gradle.properties; " +
          "this pins an identifier, not a build.",
      )
    }
    ordered.map { it.first }
  }
  val best = candidates.firstOrNull() ?: throw GradleException(
    "No installed ${platform.simctlName} simulator ${if (runtime != null) "runtime $runtime" else "runtime"}" +
      "${if (deviceType != null) " supporting device type $deviceType" else " with a ${platform.productFamily} device type"} was found. " +
      "Install a ${platform.simctlName} simulator runtime (Xcode > Settings > Components), " +
      "or set simsteward.${platform.property}.runtime and simsteward.${platform.property}.deviceType to installed identifiers.",
  )
  return SimulatorSpec(supported(best).first(), best["identifier"] as String, xcrun, slimming)
}

/** Whether both values are pinned by identifier, so resolving the spec needs no `simctl` lookup. */
internal fun pinnedByIdentifiers(runtime: String?, deviceType: String?): Boolean =
  listOf(runtime, deviceType).all { it?.startsWith("com.apple.CoreSimulator.") == true }
