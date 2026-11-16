# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added

- Initial `dev.chrisbanes.simsteward` Gradle plugin: managed simulators for Kotlin Multiplatform
  simulator tests.
- `testShards` (and `simsteward.testShards`) splits each simulator test task into shard tasks that
  run their share of the test classes on their own managed simulators, with a combined report.
  Sharding needs the configuration cache.
