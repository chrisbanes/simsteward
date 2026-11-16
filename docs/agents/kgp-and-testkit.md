# KGP simulator tests and TestKit fixtures

Simsteward manages simulators for Kotlin/Native simulator test tasks. The task
inputs and action order matter: Gradle checks task inputs before it runs the
lease action, and a task that is skipped or up-to-date never takes a lease.

## Kotlin/Native simulator test tasks

`KotlinNativeSimulatorTest` exposes `device` and `standalone` properties. KGP
provides a `device` convention that can invoke `/usr/bin/xcrun simctl list`;
simsteward sets a stable per-build, per-task device name and sets
`standalone` to `false`. At execution, the lease renames a managed simulator to
that name, then returns it to its pool name when the test finishes. The name
lookup behavior is an Xcode-dependent assumption recorded in
[ADR 0001](../adr/0001-task-named-leases.md).

KGP may set `targetName` after `configureEach` callbacks run. Simsteward records
Kotlin/Native target families as targets are configured and looks up the
platform when it needs the task's inputs or starts the lease. Do not assume
`targetName` is available while configuring the task. KGP's test run interfaces
also do not all expose the same task details, so use the public task type and
the target-family mapping rather than relying on a concrete internal run type.

KGP's test action uses `/usr/bin/xcrun simctl spawn`. The `simsteward.xcrun`
property controls simsteward's own simulator commands; it does not change that
KGP executable. Test fixtures therefore replace the KGP test action when they
need to avoid a real simulator. The real simulator smoke test covers behavior
that the fake action cannot, including KGP finding the leased simulator by its
task name.

## Shards

With `testShards > 1` and the configuration cache requested, simsteward creates KGP test runs
`shard1`…`shardN` on each simulator target from its debug test binary, in its `afterEvaluate`.
KGP registers each as an ordinary `KotlinNativeSimulatorTest` (and adds it to `allTests`), so the
per-task lease configuration applies unchanged. A shard's partition action is registered just
before the lease's `doFirst`, so it runs after the lease and before KGP's test action: it lists
classes with `simctl spawn <device> <binary> --ktest_list_tests` and sets the shard's include filter.
The original task depends on its shards through a provider that Gradle resolves after applying
`--tests`; a filtered run schedules no shards, and the original's `onlyIf` runs it only then.
Gradle's public filter API hides `--tests` patterns, so simsteward reads
`DefaultTestFilter.getCommandLineIncludePatterns()`. The combined `TestReport` uses location-only
providers and `mustRunAfter`, not dependencies, so a failing shard still finalizes into it.

In fixtures, the fake `xcrun` answers `simctl spawn` from `FixtureBuild.listTestClasses`, and the
fake test action records `shard-filter <task> <patterns>`. `setExecutionSourceFrom` points a shard at
the link output, so the fixture re-applies its fake executable after evaluation.

## Task inputs and simulator resolution

Simsteward registers the configured device type and runtime as optional task
inputs only when they are pinned. It also registers the slimming setting and
the optional profile file contents (without making its path part of the task
identity). Defaults are resolved during execution, using
`simctl list -j runtimes`: simsteward chooses the newest available runtime for
the platform and a supported device type. Pin both values to avoid this
resolution command. See [ADR 0003](../adr/0003-unpinned-specs-reresolve.md)
for the intended up-to-date behavior for unpinned values. During the up-to-date check, unpinned identifiers are resolved and compared with
task-local state from the last successful execution. A changed identifier or a
resolution failure causes the task to run; fully pinned tasks need no listing.

The managed simulator is stock by default; slimming is opt-in. With slimming
enabled, simsteward runs `simslim on` and `simslim verify` with the optional
profile before leasing it. With slimming disabled, it boots the simulator with `simctl bootstatus`. A slimming failure
fails the task setup; simsteward does not fall back to a stock simulator.

## Pools and recovery

A pool grows when no managed simulator is idle, up to `maxSimulators` (default
2). The cap belongs to a lease request, not the simulator spec. Creation runs
outside the lock, and in-flight creates count towards the cap. A returning
simulator stays unavailable until its rename back completes. The task releases
on success via `doLast` and on a root-suite failure via a listener installed at
execution; task completion events provide an idempotent backstop. Leases and
staged state are keyed by the task's build-tree path, because completion events
name an included build's task as `:<build>:<project>:<task>`, not `task.path`.

Before the first lease for each distinct configured `xcrun` and absolute normalized
records directory, the service reaps recorded orphans. `simstewardReap` always
runs an explicit reap. `xcrun` comes only from the build-wide `simsteward.xcrun`
property (including `-P`), defaulting to `/usr/bin/xcrun`; there is no
per-project override.

An ownership record is written atomically before creation, then updated with
the UDID. New records retain the configured executable; another executable's
reap skips them even when its listing lacks that simulator, and warns with the
`-Psimsteward.xcrun` that would reap them once their owner is gone. Legacy records
have no executable provenance and retain best-effort routing. The record's
executable is never itself executed. A failed delete leaves its record for
recovery. Unrecorded simulators with the prefix are reported, not deleted.
Records owned by the current daemon but not created by this build are left alone
with a warning to stop the daemon and reap; an included build in the same daemon
may still be using them.

Owner identity uses the PID and Java process birth time. When available and
consistent with Java's timestamp, a record also stores independently read kernel
birth seconds from `/bin/ps`. If Java birth metadata becomes unavailable, valid
birth seconds can establish PID reuse. With Java birth time unknown, missing,
invalid, inconsistent or denied identity evidence retains the owner and names
the record in a warning; legacy
records without fallback evidence are retained when Java birth time is unknown.
Matching seconds conservatively retain an owner even if a PID was reused within
that second. No age or command-name heuristic permits deletion.

Each lease checks device names before renaming to avoid ambiguous `simctl`
lookup. The first lease keeps a fresh listing too: creation and boot/slimming
happen after reaping, outside the lock, and other pools can create or return
simulators meanwhile. The reaping listing cannot establish current name
uniqueness. See [ADR 0001](../adr/0001-task-named-leases.md) for the naming decision.

## TestKit fixtures

Fixtures resolve the published simsteward plugin next to a real Kotlin
Multiplatform plugin. Avoid `withPluginClasspath()`: putting the plugin on the
injected classpath can place it above KGP's classloader, where the plugin cannot
load KGP types. Publishing into the build's local test repository also lets
fixtures exercise the configured Gradle and KGP compatibility matrix, as
consumers do. This follows the classloader constraint behind
[ADR 0002](../adr/0002-project-plugin.md).

The fixture applies simsteward after configuring targets, then replaces each
simulator test action with a `doFirst` action. Simsteward registers its lease
action after evaluation; Gradle prepends that later `doFirst`, so the lease is
acquired first. A passing fixture run records
the task inputs and action without invoking KGP's real simulator work; a
failing run reports the root test suite to task listeners before throwing, so
release-on-failure behavior is exercised too.

KGP marks its executable as an input and skips an empty input collection. The
fixture points it at an empty file, replaces the task dependencies with its stub
producer, and avoids a Kotlin/Native compile. KGP may disable a simulator test
when host and target architectures differ; the arm64 CI jobs use Apple silicon.
These KGP observations come from the supported fixture versions and may change.

KGP may declare Swift-import search-path files as task inputs. The fixture
provides these through a stub task, rather than writing them during
configuration: task outputs work with the configuration cache and are
recreated after build cleanup. When a fixture task action needs values, read
them inside `configureEach` rather than capturing top-level build-script
variables in an action; those script references can break configuration-cache
serialization. Kotlin DSL scripts that use `File` should import
`java.io.File`, because `java` otherwise resolves to the Kotlin extension.

The build assigns a separate TestKit directory to each test task and checkout;
fixtures within that task share its Gradle user home and ownership records.
Tests that create records must use distinct names and clean them up. Parallel
lease tests hold all slots and observe exact requested task paths through a
method on the shared service, under the same monitor as the lease wait. The
fixture captures the service provider during configuration and resolves the
implementation method at execution; it does not serialize the service or method.
Positive and no-waiter controls exercise this boundary and cache replay. Latch
the barrier once it opens so a returned lease cannot make other holders miss it.
The failing fixture still reflects Gradle's listener subscriptions to dispatch
the root completion; the real simulator smoke test also covers failure release.
Use a single worker for tests that require sequential
task execution: `--no-parallel` does not serialize tasks restored from the
configuration cache.

Use `FixtureBuild.simsteward` to configure the extension: the fixture applies
the plugin after its `plugins` block, so a type-safe accessor is unavailable.
`addModule`, `appendTo`, `installRuntimes` and `listDevices` set up multi-project
and resolution cases. When the extension is disabled, simsteward leaves KGP's
task defaults untouched.

The fakes record simulator and slimming commands and can fail selected command
prefixes. Use distinct simulated device identifiers when testing pool growth.
A passing fixture emits no test events and exercises `doLast`; a failing fixture
synthesizes a root-suite completion event to exercise failure cleanup. These are
test harness details; they do not change the plugin's public configuration.

## Compatibility and simulator smoke test

The version matrix is in `.github/workflows/build.yml`. The minimum Gradle and
KGP versions are declared in `build.gradle.kts` and published as plugin
metadata; the matrix exercises those minimums and a separately configured
current version pair. Treat the matrix as the versions this repository tests,
not proof that they are the latest available releases. When changing it,
update the workflow and check the declared metadata together.

The simulator smoke test is opt-in locally with `./gradlew smokeTest` and runs
in CI on Apple silicon with Xcode and SimSlim. It exercises a real Kotlin/Native
simulator task, including KGP's direct `/usr/bin/xcrun` use. On a fresh CI
worker, Kotlin/Native may be downloaded while linking; the smoke test links
once before its measured simulator runs so toolchain setup does not invalidate
the subsequent configuration-cache run.
