# CLAUDE.md — :core-test

**Last Updated:** 2026-09-29

Guidance for Claude Code when working in `:core-test`. Repo-wide rules live in
the [root CLAUDE.md](../CLAUDE.md).

`:core-test` is a shared **test-fixture library** (KMP, `commonMain`/`jvmMain`
only). It exists so other modules can reuse fixtures from their test scopes. Its
only test is `RepeatedTestCapExtensionTest` (see Contents).

## Design Rules

- **Fixtures live in the main source sets (`commonMain`, plus `jvmMain` for
  JVM-only helpers), never in this library's test source sets**, so consuming
  modules can use them from their test scopes: `core/commonTest`,
  `desktop-ui/test`, `dispatcher-agent/test`, `fast-sim/linuxX64Test`.
- AssertK is exposed as `api(...)` on purpose — consumers get it transitively.
- The ktlint plugin is deliberately NOT applied here; detekt runs with the
  permissive root `detekt.yml`.

## Contents

- `src/commonMain/kotlin/.../testutil/` — `CommonTestFixtures`, `TestTopologies`,
  `TestContextBuilder`, `MockSimulationContext`, `CommonKoinTestBase`,
  `SharedSimulationTestScopesModule` (shared Koin test bindings, Issue #1029),
  `ContextTracker`, `ShuntingLoopRuns`, `ArrivalTally`, `runSampled`/`sameStatic`/`separatorLabel`
  (sampling-regression helpers), and others.
- `src/jvmMain/kotlin/.../testutil/` — JVM-only helpers (`TestFixtures`,
  `NavigationDecoratingContext`, `ShuntingLoopLookups`,
  `TrainKinematicSampler`/`TrainKinematicSample` and `AspectFlipOnce`
  (Issue #989 sampling/aspect-flip helpers; cadence pinned by
  `:core` jvmTest `TrainKinematicSamplerContractTest`), and `RepeatedTestCapExtension`
  (Issue #1110 `@RepeatedTest` cap, registered in
  `src/jvmMain/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`).
  The caps follow the `heavy-test` tag and are configurable through gradle.properties
  (`testRepeatMaxCount` / `heavyTestRepeatMaxCount`, passed by the root build script as
  `interlockSim.test.repeat.*` system properties; `DEFAULT_CAP`/`HEAVY_CAP` are the fallbacks).
- `src/jvmTest/kotlin/.../testutil/RepeatedTestCapExtensionTest.kt` — the one test of
  this module. It runs `cap-fixture`-tagged fixtures through EngineTestKit; the module's
  `jvmTest` task excludes that tag (plus `integration-test` and `heavy-test`, like every
  other JVM unit-test task). Each JVM module also keeps a `RepeatedTestCapWiringTest`
  tripwire proving the extension is wired into its own test tasks.
- `src/commonMain/resources/cz/vutbr/fit/interlockSim/xml/fixtures/` — 25 XML
  fixture networks, from `minimal-network.xml` up to `praha-hlavni-nadrazi.xml`,
  including six `invalid-*.xml` negative cases. This directory is one of the
  roots baked into `:core`'s generated `NATIVE_RESOURCE_ROOTS` — see
  [core/CLAUDE.md](../core/CLAUDE.md).
