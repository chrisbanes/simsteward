# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 0.0.1 - 2026-11-16

First release, published to Maven Central. Requires JDK 17, Gradle 8.14 and Kotlin Gradle plugin
2.2.0 or newer.

### Added

- Initial `dev.chrisbanes.simsteward` Gradle plugin: managed simulators for Kotlin Multiplatform
  simulator tests. The build creates iOS, tvOS and watchOS simulators, leases one to each simulator
  test task and deletes them when the build ends. It never touches a simulator it did not create.
  ([#14](https://github.com/chrisbanes/simsteward/pull/14))
- Test tasks with the same simulator spec share a pool, which grows up to `maxSimulators`; a task
  waits for a returned lease when its pool is full. Up-to-date and skipped tasks never create a
  simulator, and an unpinned task reruns once a newer runtime is installed.
  ([#46](https://github.com/chrisbanes/simsteward/pull/46))
- Pin a platform's device type and runtime by name, such as `iPhone 16` and `iOS 18.2`, or by
  identifier. ([#53](https://github.com/chrisbanes/simsteward/pull/53))
- Optional slimming with SimSlim (`slim` and a per-platform `profile`). Managed simulators are stock
  unless slimming is turned on. ([#48](https://github.com/chrisbanes/simsteward/pull/48))
- Orphaned simulators left by a killed daemon or crash are reaped by the next build that leases a
  simulator, or on demand with `simstewardReap`.
  ([#44](https://github.com/chrisbanes/simsteward/pull/44))
- Build-wide defaults as `simsteward.*` Gradle properties, per-module overrides in the
  `simsteward {}` extension, and `simsteward.enabled=false` to opt a project out.
- `testShards` (and `simsteward.testShards`) splits each simulator test task into shard tasks that
  run their share of the test classes on their own managed simulators, with a combined report.
  Sharding needs the configuration cache.
