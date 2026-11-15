# Leased simulators are renamed to a stable per-task name

KGP's simulator test task declares its `device` as an `@Input`. If simsteward passed a fresh UDID each build, managed simulator tests could never be up-to-date. Instead, each test task's `device` is a fixed name derived from the build root and the task path. When the task actually runs, its lease renames an idle pool simulator to that name, and returning the lease renames it back. Tasks that are up-to-date or skipped never lease, so they never cause a simulator to be created.

## Consequences

- This relies on `simctl` resolving a device by name, which its help does not document (it lists only a UDID or `booted`). A spike on Xcode with iOS 27.0 confirmed that renaming a booted simulator works and that `spawn` and `getenv` resolve the new name.
- If two simulators share a name, `simctl` silently resolves one of them. A lease must therefore confirm that its name is unique before handing it out, and must reap or fail otherwise.
- If a future Xcode drops name lookup, fall back to fresh UDIDs from a lazy provider with `doNotTrackState`, accepting that tests always rerun.
