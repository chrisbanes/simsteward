# Simsteward

Simsteward gives a Gradle build's Kotlin Multiplatform simulator tests their own Apple simulators: the build creates them, lends them to test tasks and deletes them when the build ends.

## Simulators

**Managed simulator**:
A simulator that one build creates, owns and deletes. Simsteward never selects, resets or deletes a simulator it did not create.
_Avoid_: Owned, disposable, isolated, ephemeral simulator

**Platform**:
The Apple simulator family a test needs: iOS, tvOS or watchOS. Each managed simulator serves exactly one platform.
_Avoid_: Target, OS

**Slim**:
A managed simulator whose unneeded background services SimSlim has disabled according to a profile.

**Stock**:
A simulator with every background service running. A managed simulator stays stock unless slimming is turned on.
_Avoid_: Plain, normal, unslimmed

**Profile**:
A SimSlim description of which background services a slim simulator keeps running.

**Simulator spec**:
Everything that makes two managed simulators interchangeable: platform, device type, runtime, whether it is slim or stock, and the profile.
_Avoid_: Config, device spec

**Pin**:
A device type or runtime fixed in configuration for a platform, instead of the default simsteward picks: the newest installed runtime and a device type it supports.
_Avoid_: Lock, fix, override

## Sharing

**Pool**:
The managed simulators of one simulator spec within one build. A pool grows only when no simulator is idle, up to a cap.

**Lease**:
One test task's exclusive use of one managed simulator from a pool, from the moment the task starts until it finishes or fails.
_Avoid_: Checkout, borrow, acquire

**Shard**:
One of N tasks a simulator test task is split into, each holding its own lease and running a deterministic share of the test classes.
_Avoid_: Fork, partition, split task

## Recovery

**Orphan**:
A managed simulator whose owning build ended without deleting it, for example after a killed daemon or a crash.
_Avoid_: Leak, stale simulator

**Reap**:
To delete orphans. Only simulators confirmed to be orphans are reaped.
_Avoid_: Clean, purge
