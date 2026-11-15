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
 * `simctl list -j runtimes`, read only when something is unpinned. A pinned [runtime] or [deviceType] overrides the pick.
 */
internal fun resolveSpec(
  platform: Platform,
  runtimesJson: () -> String,
  runtime: String?,
  deviceType: String?,
  xcrun: String,
  slimming: Slimming,
): SimulatorSpec {
  if (runtime != null && deviceType != null) return SimulatorSpec(deviceType, runtime, xcrun, slimming)
  val runtimes = ((JsonSlurper().parseText(runtimesJson()) as Map<*, *>)["runtimes"] as List<*>)
    .map { it as Map<*, *> }
    .filter { it["platform"] == platform.simctlName && it["isAvailable"] == true }
    .filter { runtime == null || it["identifier"] == runtime }
  fun supported(r: Map<*, *>) = (r["supportedDeviceTypes"] as List<*>).map { it as Map<*, *> }
    .filter { deviceType?.let { pinned -> it["identifier"] == pinned } ?: (it["productFamily"] == platform.productFamily) }
    .map { it["identifier"] as String }

  // shortcut: reads a non-numeric version component as 0; parse it properly if simctl ever reports one.
  fun version(r: Map<*, *>) = (r["version"] as String).split('.').map { it.toIntOrNull() ?: 0 }
  val newestFirst = Comparator<List<Int>> { a, b ->
    a.zip(b).map { (x, y) -> y.compareTo(x) }.firstOrNull { it != 0 } ?: b.size.compareTo(a.size)
  }
  val candidates = runtimes.filter { supported(it).isNotEmpty() }.sortedWith(compareBy(newestFirst, ::version))
  val best = candidates.firstOrNull() ?: throw GradleException(
    "No installed ${platform.simctlName} simulator ${if (runtime != null) "runtime $runtime" else "runtime"}" +
      "${if (deviceType != null) " supporting device type $deviceType" else " with a ${platform.productFamily} device type"} was found. " +
      "Install a ${platform.simctlName} simulator runtime (Xcode > Settings > Components), " +
      "or set simsteward.${platform.property}.runtime and simsteward.${platform.property}.deviceType to installed identifiers.",
  )
  return SimulatorSpec(deviceType ?: supported(best).first(), best["identifier"] as String, xcrun, slimming)
}
