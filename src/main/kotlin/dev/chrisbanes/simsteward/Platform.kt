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
 * `simctl list -j runtimes`. A pinned [runtime] or [deviceType] overrides the pick.
 */
internal fun resolveSpec(
  platform: Platform,
  runtimesJson: String,
  runtime: String?,
  deviceType: String?,
  xcrun: String,
  slimming: Slimming,
): SimulatorSpec {
  val runtimes = ((JsonSlurper().parseText(runtimesJson) as Map<*, *>)["runtimes"] as List<*>)
    .map { it as Map<*, *> }
    .filter { it["platform"] == platform.simctlName && it["isAvailable"] == true }
    .filter { runtime == null || it["identifier"] == runtime }
  fun supported(r: Map<*, *>) = (r["supportedDeviceTypes"] as List<*>).map { it as Map<*, *> }
    .filter { deviceType?.let { pinned -> it["identifier"] == pinned } ?: (it["productFamily"] == platform.productFamily) }
    .map { it["identifier"] as String }

  // shortcut: compares dotted numeric versions only; fine until simctl reports non-numeric ones.
  val candidates = runtimes.filter { supported(it).isNotEmpty() }
    .sortedWith(compareByDescending<Map<*, *>> { r -> (r["version"] as String).split('.').map { it.toIntOrNull() ?: 0 }.fold(0L) { acc, n -> acc * 1000 + n } })
  val best = candidates.firstOrNull() ?: throw GradleException(
    "No installed ${platform.simctlName} simulator ${if (runtime != null) "runtime $runtime" else "runtime"}" +
      "${if (deviceType != null) " supporting device type $deviceType" else " with a ${platform.productFamily} device type"} was found. " +
      "Install a ${platform.simctlName} simulator runtime (Xcode > Settings > Components), " +
      "or set simsteward.${platform.property}.runtime and simsteward.${platform.property}.deviceType to installed identifiers.",
  )
  return SimulatorSpec(deviceType ?: supported(best).first(), best["identifier"] as String, xcrun, slimming.slim, slimming.simslim, slimming.profile)
}
