# Simsteward

Simsteward is a Gradle plugin for Kotlin Multiplatform builds. It gives the build's Apple simulator tests their own managed simulators: the build creates them, lends one to each test task as a lease, and deletes them when the build ends. Simulators can optionally be slimmed with SimSlim.

Simsteward never selects, resets or deletes a simulator it did not create, so the simulators you use day to day are left alone.

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

Simsteward is a project plugin, not a settings plugin: applying it opts that project in. Apply it to each Kotlin Multiplatform project whose simulator tests it should manage. In a project without Kotlin Multiplatform it does nothing.

## How it works

Simsteward takes over every iOS, tvOS and watchOS simulator test task (such as `iosSimulatorArm64Test`) in the project.

Managed simulators with the same simulator spec (platform, device type, runtime, and whether they are slim or stock) form a pool, shared by every project in the build. When a test task starts, it leases an idle simulator from its pool. If none is idle, the pool creates one, up to `maxSimulators`. Once the pool is at its cap, the task waits for another task to return its lease, and logs that it is waiting. A task returns its lease when its tests finish or fail.

While leased, a simulator is renamed after the task that holds it, for example `simsteward-1a2b3c4d:app:iosSimulatorArm64Test`; the hash comes from the build's root directory. Between leases it goes back to its pool name, such as `simsteward-5e6f7a8b`. At the end of the build, simsteward deletes every simulator it created.

## Configuration

Set the build's defaults as Gradle properties in the root project's `gradle.properties`. Every project that applies simsteward reads them:

```properties
simsteward.slim=true
simsteward.maxSimulators=3
simsteward.ios.deviceType=com.apple.CoreSimulator.SimDeviceType.iPhone-16
simsteward.ios.runtime=com.apple.CoreSimulator.SimRuntime.iOS-18-2
```

Then, where a module needs something different, override those values in that module's `simsteward {}` block:

```kotlin
simsteward {
  maxSimulators.set(1)
  ios {
    deviceType.set("com.apple.CoreSimulator.SimDeviceType.iPad-Air-11-inch-M2")
  }
}
```

The `simsteward {}` block only configures the project it is in. A block in the root project's build file does not reach its subprojects, so put build-wide defaults in `gradle.properties` instead. Gradle's Isolated Projects stops one project from reading another's configuration.

| Gradle property | Extension | Default | |
|---|---|---|---|
| `simsteward.enabled` | `enabled` | `true` | `false` leaves this project's test tasks as Kotlin configures them. |
| `simsteward.slim` | `slim` | `false` | Slims new managed simulators with SimSlim. |
| `simsteward.maxSimulators` | `maxSimulators` | `2` | The most simulators one pool may hold. Must be at least 1. |
| `simsteward.testShards` | `testShards` | `1` | Splits each simulator test task into this many shards; see [Test shards](#test-shards). Must be at least 1. |
| `simsteward.<platform>.deviceType` | `<platform> { deviceType }` | unset | Pins the device type identifier. |
| `simsteward.<platform>.runtime` | `<platform> { runtime }` | unset | Pins the runtime identifier. |
| `simsteward.<platform>.profile` | `<platform> { profile }` | unset | Path to the SimSlim profile for slim simulators. |
| `simsteward.xcrun` | | `/usr/bin/xcrun` | Path to `xcrun`. |
| `simsteward.simslim` | | `simslim` | Path to SimSlim, when it is not on the Gradle daemon's `PATH`. |

`<platform>` is `ios`, `tvos` or `watchos`. `xcrun` and `simslim` are build-wide and have no extension setting.

`maxSimulators` is not part of the simulator spec. Projects that share a pool each apply their own cap when they lease from it.

A relative `profile` path resolves against the build's root directory when it is set by property, and against the project directory when it is set in the extension.

### Pinning

When the device type or runtime is not pinned, simsteward picks the newest installed runtime for the platform and a device type that runtime supports. Pin both to keep a test task on a fixed simulator, whatever Xcode installs.

An unpinned task is not up to date once the pick changes, so installing a newer runtime reruns its tests on the next build.

You find the identifiers to pin with `xcrun simctl list runtimes` and `xcrun simctl list devicetypes`.

## Slim and stock simulators

Managed simulators are stock by default: every background service runs, as on a simulator you create yourself. Simsteward boots each one before its first lease.

With slimming on, simsteward runs `simslim on` and `simslim verify` on each new managed simulator, which disables the background services its profile does not keep. Without a `profile`, simsteward passes none and SimSlim's own default applies. If your tests depend on a service the profile turns off, give that platform a profile that keeps it.

Slimming needs [SimSlim](https://github.com/MobAI-App/simslim):

```shell
brew install mobai-app/tap/simslim
```

If slimming a simulator fails, the test task fails. Simsteward does not fall back to a stock simulator.

Slim and stock simulators, and slim simulators with different profiles, never share a pool.

## When things go wrong

**No installed runtime**: if no installed runtime matches the platform, or the pinned runtime or device type, the task fails. Install a runtime in Xcode > Settings > Components, or pin identifiers that are installed.

**Several runtimes with the same version**: if two installed runtime identifiers share the newest version, simsteward cannot pick between them and the task fails, naming both. Pin one with `simsteward.<platform>.runtime`.

**`xcrun` or SimSlim cannot start**: the Gradle daemon may not have the same `PATH` as your shell. Set `simsteward.xcrun` or `simsteward.simslim` to the tool's full path.

**Another simulator has the task's name**: `simctl` cannot tell two simulators with the same name apart, so the task fails rather than risk testing on the wrong one. This usually means another build of the same checkout is running that task; wait for it to finish. Otherwise, delete or rename the simulator the error names.

**A simulator cannot be deleted at the end of the build**: simsteward reports the failure and keeps the simulator's record, so a later build reaps it as an orphan.

## Test shards

A module with a large simulator test suite can split each simulator test task across several managed simulators:

```kotlin
simsteward {
  testShards.set(4)
}
```

`testShards` (or the `simsteward.testShards` property; default 1, off) splits each simulator test task in the project into that many shard tasks, such as `iosSimulatorArm64Shard1Test`. Each shard leases its own simulator, lists the test binary's classes, and runs every Nth class in sorted order. `iosSimulatorArm64Test` runs its shards instead of its own tests, so `iosSimulatorArm64Test`, `allTests` and `check` work unchanged. With `--tests`, the original task runs alone, as it does from the IDE.

Shards run in parallel only as far as these limits allow:

- **Gradle workers.** Each shard is an ordinary task, so Gradle runs at most `--max-workers` of them at once. With one worker they run one after another, even from the configuration cache.
- **`maxSimulators`.** A shard leases from the same pool as every other test task with that simulator spec in the build. Once the pool is at its cap, further shards wait for a returned lease and log that they are waiting, then reuse that simulator.
- **Simulator creation.** A shard that needs a new simulator starts its tests only once that simulator is created and booted, or slimmed. Shards create their simulators concurrently, but creation time varies, so one shard can finish before another's simulator is ready. Their tests then do not overlap, even though neither waited for the other.

The original task runs no tests of its own, so `--rerun` or `clean<Task>` on it reruns nothing; use `--rerun-tasks` or clean the shard tasks. Shards copy the original task's `enabled`, `ignoreFailures` and exclude filters when they are created. Anything else set only on the original, such as `onlyIf` conditions, `environment` or `testLogging`, does not reach them; configure `tasks.withType<KotlinNativeSimulatorTest>()` instead. A shard replaces any include filter set on it with its share. Requesting a filtered original together with `check` or `allTests` runs the shards as well.

Each shard writes its own results under `build/test-results/<shard task>`, and a combined HTML report lands in the original task's report folder. Sharding needs the [configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html), because only then does Gradle run one project's tasks in parallel; without it, simsteward does not shard and says so.

## Orphaned simulators

If a build dies before deleting its simulators, for example when the Gradle daemon is killed, they become orphans. Simsteward records each managed simulator it creates, and its owning Gradle daemon, under `simsteward/records` in the Gradle user home (`~/.gradle` by default). A simulator is an orphan only once its owning daemon has exited.

The next build that leases a simulator reaps orphans first. To reap them without running tests, run:

```shell
./gradlew simstewardReap
```

Reaping leaves these alone:

- **Simulators owned by a running Gradle daemon.** They may still be in use. If no build is using them, stop the daemon with `./gradlew --stop`, then run `simstewardReap`.
- **Simulators created with a different `xcrun`.** Reaping warns about them; reap them with `./gradlew simstewardReap -Psimsteward.xcrun=<path>`, using the path the warning names.
- **Simulators named `simsteward-…` with no record.** Simsteward cannot confirm that they are orphans, so it only warns. Delete one yourself with `xcrun simctl delete <udid>` once you know no build is using it.

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
