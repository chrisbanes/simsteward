# Simsteward

Simsteward is a Gradle plugin for Kotlin Multiplatform builds. It gives the build's Apple simulator tests their own managed simulators: the build creates them, lends one to each test task as a lease, and deletes them when the build ends. Simulators are slimmed with SimSlim by default.

## Setup

Requires Gradle 8.14 and Kotlin Gradle plugin 2.2.0 or newer.

In `settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    mavenCentral()
  }
}
```

In the build file of a Kotlin Multiplatform project:

```kotlin
plugins {
  id("dev.chrisbanes.simsteward") version "<version>"
}
```

## Configuration

The `simsteward {}` extension overrides, per project, the matching `simsteward.*` Gradle properties:

```kotlin
simsteward {
  slim.set(false)
  maxSimulators.set(3)
  ios {
    deviceType.set("<device type identifier>")
    runtime.set("<runtime identifier>")
  }
}
```

`enabled`, `slim` and `maxSimulators` default to `true`, `true` and `2`. `ios`, `tvos` and `watchos` each take `deviceType`, `runtime` and `profile`; unset values are chosen automatically.

## Snapshots

Snapshots of `0.1.0-SNAPSHOT` are published to Central Portal Snapshots:

```kotlin
pluginManagement {
  repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
  }
}
```
