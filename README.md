# Simsteward

Simsteward is a Gradle plugin for Kotlin Multiplatform builds. It gives the build's Apple simulator tests their own managed simulators: the build creates them, lends one to each test task as a lease, and deletes them when the build ends. Simulators can optionally be slimmed with SimSlim.

## Setup

Requires JDK 17, Gradle 8.14 and Kotlin Gradle plugin 2.2.0 or newer.

In `settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    mavenCentral()
  }
}
```

In the build file of a Kotlin Multiplatform project, apply the plugin in the same `plugins {}` block as Kotlin Multiplatform (or in a project below the one that declares it), so the Kotlin Gradle plugin is visible to simsteward:

```kotlin
plugins {
  kotlin("multiplatform") version "<kotlin version>"
  id("dev.chrisbanes.simsteward") version "<version>"
}
```

## Configuration

The `simsteward {}` extension overrides, per project, the matching `simsteward.*` Gradle properties:

```kotlin
simsteward {
  slim.set(true)
  maxSimulators.set(3)
  ios {
    deviceType.set("<device type identifier>")
    runtime.set("<runtime identifier>")
  }
}
```

`enabled`, `slim` and `maxSimulators` default to `true`, `false` and `2`. `ios`, `tvos` and `watchos` each take `deviceType`, `runtime` and `profile`; unset values are chosen automatically.

Slimming (`slim`, or the `simsteward.slim=true` property) needs [SimSlim](https://github.com/MobAI-App/simslim) (`brew install mobai-app/tap/simslim`). Two build-wide Gradle properties locate the tools when they are not on the Gradle daemon's `PATH` or at the default location: `simsteward.simslim` (default `simslim`) and `simsteward.xcrun` (default `/usr/bin/xcrun`).

## Orphaned simulators

If a build dies before deleting its simulators, the next build that leases one deletes them. To delete them without running tests, run `./gradlew simstewardReap`. Simulators owned by a Gradle daemon that is still running are left alone; stop it with `./gradlew --stop` first.

## Snapshots

Snapshots of the next development version are published to Central Portal Snapshots:

```kotlin
pluginManagement {
  repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
  }
}
```
