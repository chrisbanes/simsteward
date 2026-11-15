# Simsteward is a project plugin, not a settings plugin

We first chose a settings plugin so that one block in `settings.gradle.kts` could configure every project. A spike on Gradle 9.9.1 and KGP 2.3.21 showed that a settings plugin cannot see KGP's types: Gradle loads it before KGP, so `KotlinNativeSimulatorTest` fails with `NoClassDefFoundError`. The two workarounds both had costs. Reflection tied simsteward to KGP internals with no compile-time checks. Declaring KGP in settings forced a single KGP version on the whole build and added a setup step for consumers. So simsteward is a project plugin, applied in each KMP module, where it is loaded alongside KGP and uses KGP's API directly. Applying the plugin opts a project in.

## Consequences

- KGP must be visible from wherever simsteward is applied: apply it in the same `plugins {}` block as KGP, or in a project below the one that declares KGP.
- Isolated Projects stops one project from reading another's extension, so configuration that applies to the whole build cannot live in a root-project extension.
- Projects register the shared lease service in whatever order Gradle configures them, so the service cannot rely on build-wide parameters. Each lease request carries its own simulator spec.
