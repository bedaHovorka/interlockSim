# CLAUDE.md — :core-test

**Last Updated:** 2026-08-28

Guidance for Claude Code when working in `:core-test`. Repo-wide rules live in
the [root CLAUDE.md](../CLAUDE.md).

`:core-test` is a shared **test-fixture library** (KMP, `commonMain`/`jvmMain`
only). It has **no tests of its own** — it exists so other modules can reuse
fixtures from their test scopes.

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
  `ShuntingLoopRuns`, `ArrivalTally`, `runSampled`/`sameStatic`/`separatorLabel`
  (sampling-regression helpers), and others.
- `src/jvmMain/kotlin/.../testutil/` — JVM-only helpers (`TestFixtures`,
  `NavigationDecoratingContext`, `ShuntingLoopLookups`).
- `src/commonMain/resources/cz/vutbr/fit/interlockSim/xml/fixtures/` — 25 XML
  fixture networks, from `minimal-network.xml` up to `praha-hlavni-nadrazi.xml`,
  including six `invalid-*.xml` negative cases. This directory is one of the
  roots baked into `:core`'s generated `NATIVE_RESOURCE_ROOTS` — see
  [core/CLAUDE.md](../core/CLAUDE.md).
