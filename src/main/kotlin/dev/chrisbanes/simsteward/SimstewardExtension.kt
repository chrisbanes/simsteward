package dev.chrisbanes.simsteward

import org.gradle.api.Action
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/**
 * Per-project settings. Each value defaults from the matching `simsteward.*` Gradle property, so
 * `gradle.properties` applies build-wide and a project overrides it here.
 */
abstract class SimstewardExtension {
  /** `simsteward.enabled`, default true. False leaves this project's test tasks as KGP configures them. */
  abstract val enabled: Property<Boolean>

  /** `simsteward.slim`, default true. */
  abstract val slim: Property<Boolean>

  /**
   * `simsteward.maxSimulators`, default 2: the most simulators one pool may hold. It is not part of
   * the simulator spec; each lease request applies its own project's cap to the shared pool.
   */
  abstract val maxSimulators: Property<Int>

  @get:Nested abstract val ios: PlatformSettings
  @get:Nested abstract val tvos: PlatformSettings
  @get:Nested abstract val watchos: PlatformSettings

  fun ios(action: Action<PlatformSettings>) = action.execute(ios)
  fun tvos(action: Action<PlatformSettings>) = action.execute(tvos)
  fun watchos(action: Action<PlatformSettings>) = action.execute(watchos)

  internal fun of(platform: Platform) = when (platform) {
    Platform.IOS -> ios
    Platform.TVOS -> tvos
    Platform.WATCHOS -> watchos
  }
}

/** Settings for one platform; each defaults from `simsteward.<platform>.<name>`, unset when absent. */
interface PlatformSettings {
  /** Simulator device type identifier; unset picks one automatically. */
  val deviceType: Property<String>

  /** Simulator runtime identifier; unset picks the newest installed. */
  val runtime: Property<String>

  /** Slimming profile path; a relative path resolves against this project, or the build root when set by property. */
  val profile: Property<String>
}
