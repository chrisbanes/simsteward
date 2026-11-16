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

`enabled`, `slim` and `maxSimulators` default to `true`, `false` and `2`; `testShards` is described under [Test shards](#test-shards). `ios`, `tvos` and `watchos` each take `deviceType`, `runtime` and `profile`; unset values are chosen automatically.

Slimming (`slim`, or the `simsteward.slim=true` property) needs [SimSlim](https://github.com/MobAI-App/simslim) (`brew install mobai-app/tap/simslim`). Two build-wide Gradle properties locate the tools when they are not on the Gradle daemon's `PATH` or at the default location: `simsteward.simslim` (default `simslim`) and `simsteward.xcrun` (default `/usr/bin/xcrun`).

## Test shards

A module with a large simulator test suite can split each simulator test task across several managed simulators:

```kotlin
simsteward {
  testShards.set(4)
}
```

`testShards` (or the `simsteward.testShards` property; default 1, off) splits each simulator test task in the project into that many shard tasks, such as `iosSimulatorArm64Shard1Test`. Each shard leases its own simulator, lists the test binary's classes, and runs every Nth class in sorted order. `iosSimulatorArm64Test` runs its shards instead of its own tests, so `iosSimulatorArm64Test`, `allTests` and `check` work unchanged. `maxSimulators` still caps how many shards run at once; the rest wait for a returned lease. With `--tests`, the original task runs alone, as it does from the IDE.

Each shard writes its own results under `build/test-results/<shard task>`, and a combined HTML report lands in the original task's report folder. Sharding needs the [configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html), because only then does Gradle run one project's tasks in parallel; without it, simsteward does not shard and says so.

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
