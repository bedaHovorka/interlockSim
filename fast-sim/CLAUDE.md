# CLAUDE.md — :fast-sim

**Last Updated:** 2026-08-27

Guidance for Claude Code when working in `:fast-sim`. Repo-wide rules live in
the [root CLAUDE.md](../CLAUDE.md).

`:fast-sim` is the native linuxX64 CLI binary (headless simulation runs). It
depends on [:core](../core/CLAUDE.md) and `:core-test` fixtures only — it must
NEVER depend on `:dispatcher-agent` (no LLM dependencies in the native binary).

## Platform Constraints

- **linuxX64 only.** The whole `kotlin {}` block sits behind `isLinuxHost`, so on
  a non-Linux host the module is still included (IDE indexing, task discovery)
  and the KMP warning "Please initialize at least one Kotlin target" is expected
  and harmless.
- **The runtime image must stay glibc-based** (`debian:12-slim`), never
  Alpine/musl. Kotlin/Native linuxX64 binaries on musl via `gcompat` hit an
  intermittent all-threads futex deadlock that hung ~20-30% of runs
  (Issue #685). Do not switch back.
- Resource lookup uses `:core`'s generated `NATIVE_RESOURCE_ROOTS` with
  **absolute** paths — a relocated binary fails at its first `Resources.read()`.

## Build, Run, Test

```bash
./gradlew buildFastSim          # Links the RELEASE executable (currently the same target as buildFastSimRelease)
./gradlew runFastSim            # Runs the DEBUG executable
./gradlew buildFastSimRelease   # Links the release executable
./gradlew runFastSimRelease     # Runs the release executable
./gradlew :fast-sim:linuxX64Test
```

These are root-level convenience tasks (`build.gradle.kts` in the repo root),
registered only on a Linux host.

Tests use `kotlin.test` only — no JUnit, no test tags. Benchmarking:
`benchmark/benchmark.sh` and [../docs/FAST_SIM_BENCHMARK.md](../docs/FAST_SIM_BENCHMARK.md).

## Code Quality

This module opts into `detekt-strict.yml` (`fast-sim/build.gradle.kts`,
`config.setFrom(...)`) because it is new Kotlin code, never converted from
Java — see the root CLAUDE.md "Code Quality" section.
