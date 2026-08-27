# CLAUDE.md

**Last Updated:** 2026-08-27

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository. It holds repo-wide rules only; module-specific guidance lives in the per-subproject
files listed under [Module Guides](#module-guides).

## Status Marker Convention

Throughout this documentation, the following status markers are used:
- ✅ **COMPLETE** - Work finished and merged
- 🟡 **IN PROGRESS** - Active work underway
- ⏸️ **BLOCKED** - Waiting on dependencies
- 🆕 **OPEN/NEW** - Not started but planned
- ❌ **CANCELLED** - Work abandoned

## Language: English Only (Critical)

**English is mandatory for ALL project output.** This is a hard rule, not a preference.

**Must be English:** runtime log messages (including interpolated values that form a
sentence), user-facing output, denial/error reason strings, exception messages, code
comments and KDoc, commit messages, PR titles/bodies, code review comments, issue text,
and all agent-to-agent and agent-to-human communication.

**Czech is permitted ONLY** as inline, genuinely untranslatable railway technical terms —
e.g. the canonical ESA-11 interlocking condition names (`Volnost jízdní cesty`, `Závěr`,
`postaveno a volno`) or a proper noun with no English equivalent. A full Czech sentence in
a log message or denial reason is never "an untranslatable term" — translate it
(`"Neznámý bod trasy: X"` must be `"Unknown route endpoint: X"`).

**Rationale:** Mixed-language logs make runtime diagnostics, grep-based debugging, and
external review unreadable.

**Enforcement:** Code review (kotlin-tech-lead) rejects any new/changed code that introduces
non-English log or reason strings. When fixing existing Czech strings, also update the tests
that assert on those strings. Do not add `@Suppress` or comment workarounds to bypass this rule.

## Agent Team Structure

@TEAM.md

**[TEAM.md](TEAM.md)** defines the 7 specialized agent roles, their decision authority
hierarchy, collaboration patterns, and the railway-inspired A2A communication protocols.

## Project Overview

Railway Interlocking Simulator - A BSc thesis project (2006/2007) from Brno University of
Technology that simulates railway interlocking systems with a graphical editor and discrete
event simulation engine.

[![Gradle Build with Java 21](https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml/badge.svg)](https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml)
[![SonarQube Analysis](https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml/badge.svg)](https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml)

## Module Guides

Each Gradle subproject has its own `CLAUDE.md` with module-specific commands, architecture,
and rules. Claude Code loads them automatically when working with files in that module:

- [core/CLAUDE.md](core/CLAUDE.md) — KMP domain model, simulation engine, XML;
  **sim/ package rules**; purity gate; known issues
- [core-test/CLAUDE.md](core-test/CLAUDE.md) — shared test-fixture library
  (fixtures in `commonMain`; 25 XML fixture networks)
- [dispatcher-agent/CLAUDE.md](dispatcher-agent/CLAUDE.md) — Goal 10 LLM dispatcher,
  Ollama setup, **manual-only `aiSweep`**
- [desktop-ui/CLAUDE.md](desktop-ui/CLAUDE.md) — Swing GUI, entry point and its six
  modes, Goal 7 speed control, `shadowJar`
- [fast-sim/CLAUDE.md](fast-sim/CLAUDE.md) — native linuxX64 CLI binary;
  glibc-only runtime; the one `detekt-strict.yml` module

## Build System

Gradle with Kotlin DSL. Java 21 LTS is required — on this machine use
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`. The January 2026 migrations (Ant→Gradle,
Java 11→21, Java→Kotlin, kDisco extraction) are history; see
[docs/JAVA21-MIGRATION-SUMMARY.md](docs/JAVA21-MIGRATION-SUMMARY.md).

### Common Build Commands

```bash
./gradlew clean build             # Build and test
./gradlew test                    # Unit tests only
./gradlew integrationTest         # Integration tests only

# Run tasks (defined in :desktop-ui — see desktop-ui/CLAUDE.md for the full list)
./gradlew runSim                  # Pre-configured shunting loop
./gradlew runEditor               # Editor GUI
./gradlew runExampleGui           # Animated GUI simulation with speed control
```

Full build-system documentation (dependency management, GitHub Packages authentication,
manual JAR execution): [docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md) under
"Build & Development Environment".

### Directory Structure

Gradle subprojects (see `settings.gradle.kts`):

- `core/` - KMP `:core` (domain model, simulation engine, XML); targets `jvm` + `linuxX64`
- `core-test/` - KMP `:core-test` (shared test fixtures; `commonMain`/`jvmMain` only)
- `dispatcher-agent/` - JVM `:dispatcher-agent` (Goal 10 dispatcher: Koog agents, Ollama, sweep)
- `desktop-ui/` - JVM `:desktop-ui` (GUI, DI bootstrap, `Main` entry point)
- `fast-sim/` - native `:fast-sim` (linuxX64 CLI binary, requires Linux host; included only when present)

Other locations:

- `docs/` - Project documentation
- `text/` - LaTeX thesis sources
- `desktop-ui/build/libs/interlockSim.jar` - Packaged application (produced by `shadowJar`)

## Docker Quick Start

```bash
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token
docker compose build app                    # App image only — runs NO tests
docker compose --profile test build test    # Full test suite + quality gates (tests run DURING the image build)
docker compose up app                       # Editor GUI
docker compose up text                      # Thesis PDF
```

Key facts (full detail: [docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md) under
"Build & Development Environment"):

- Tests are decoupled from the app image; the suite runs in the separate `test-runner`
  stage, during that image's build (BuildKit cache mounts and secrets exist only at build time).
- `GITHUB_TOKEN` is passed as a BuildKit secret, never a build `ARG` — it cannot leak
  into build logs or image history.
- **Builder and test stages must stay non-root** (they run as `builder`, UID/GID 1001):
  the suite contains filesystem-permission tests that silently auto-skip under root,
  so running tests as root drops coverage without any failure.
- The runtime user's UID/GID comes from the `RUNTIME_UID`/`RUNTIME_GID` build args so
  the container can read the host's 0600 X11 cookie. Set
  `export RUNTIME_UID=$(id -u) RUNTIME_GID=$(id -g)` (or put them in `.env`) before
  building; details in the comments in `docker-compose.yml` and `Dockerfile`.
- The fast-sim runtime image must stay glibc-based — see
  [fast-sim/CLAUDE.md](fast-sim/CLAUDE.md).
- Offline builds: publish kDisco to `mavenLocal()` first — see the style guide's
  "Dependency Management".

## Code Style

Follows `.editorconfig`: Kotlin/Java files use tabs (width 4) with max line length 120,
XML files use 2 spaces, UTF-8 encoding, LF line endings. Full conventions:
[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md).

## Code Modification Guidelines

1. **Tests are mandatory** - Modified code MUST be covered by tests
2. **Align with goals** - Support LONG_TERM_GOALS.md objectives
3. **No breaking changes** - Maintain backward compatibility
4. **Document decisions** - Update relevant documentation
5. **Quality gates** - Must pass: `./gradlew build detekt ktlintCheck test`

The `sim/` package has stricter, conservative rules — see
[core/CLAUDE.md](core/CLAUDE.md) ("sim/ Package Rules"). Detailed
allowed/restricted/prohibited examples: [docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)
under "Code Modification Guidelines".

## Testing

JUnit 5 with AssertK assertions. Tag vocabulary:

- *(untagged)* - unit tests; run with `./gradlew test`
- `integration-test` - run with `./gradlew integrationTest`
- `heavy-test` - manual-only stress tests; run with `heavyTest` (policy below)
- `ollama-test` - needs a live Ollama; `:dispatcher-agent` only (see
  [its guide](dispatcher-agent/CLAUDE.md))

The default pre-push gate is `test` + `integrationTest`.

**Heavy tests are manual-only and never run in CI.** `test`, `integrationTest`, and
`build` all exclude them. Launch `heavyTest` only in these situations:

- After changes to simulation logic — path reservation, train physics, kDisco event
  scheduling, or anything in `sim/`
- After changes to concurrency primitives — `waitUntil`/`hold` patterns,
  `Process`/`Continuous` lifecycle, kDisco scheduling internals
- When investigating intermittent failures — deadlocks, races, leaks, flaky reports
- Before merging branches that touch `sim/`, `context/navigation/`, or kDisco integration

```bash
./gradlew :core:heavyTest
./gradlew :desktop-ui:heavyTest
./gradlew :dispatcher-agent:heavyTest
```

Repetition caps (Gradle-enforced via `junit.jupiter.params.repeat.maxCount`):
`test`/`integrationTest` max **50** repetitions per test method; `heavyTest` up to **1000**.
Both `test` and `integrationTest` call `excludeTags("heavy-test")`, so a mistagged heavy
test cannot regress into CI.

The manual-only `aiSweep` dispatcher sweep belongs to the same category — see
[dispatcher-agent/CLAUDE.md](dispatcher-agent/CLAUDE.md).

For current coverage numbers run `./gradlew test jacocoTestReport` or read the CI coverage
artifact. Test utilities (`TestFixtures`, `TestTopologies`, `TestContextBuilder`,
`AssertKExtensions`): [docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md) under
"Test Fixtures and Utilities".

## Dependency Injection with Koin

Koin 3.5.6. Critical rules:

1. **sim/ package injection is allowed** — the restriction was lifted 2026-03-20
   (kDisco Phase 1 complete)
2. **Contexts are NOT singletons** - use `factory` or `scope`, never `single`
3. **Preserve factory patterns** - inject factories, not products

Full documentation (module organization, scope-per-context, testing patterns):
[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md) under
"Dependency Injection with Koin".

## Code Quality

**RULE: Disabling ktlint is forbidden.** Never set `enabled = false` on ktlint tasks or add
workaround comments to bypass enforcement. Fix violations; don't silence the checker. The
only sanctioned exception is the JMH source set — see
[desktop-ui/CLAUDE.md](desktop-ui/CLAUDE.md).

Dual-level detekt:

- `detekt.yml` - permissive rules for legacy Java→Kotlin converted code; the default
- `detekt-strict.yml` — strict rules for new Kotlin code written from scratch. It is
  **not** a separate Gradle task: a subproject opts in by pointing its own `detekt` task
  at the file; only `:fast-sim` does so today (see [fast-sim/CLAUDE.md](fast-sim/CLAUDE.md)).

```bash
./gradlew detekt              # All subprojects
./gradlew ktlintCheck         # Formatting check
./gradlew ktlintFormat        # Auto-format
```

SonarQube/SonarCloud analysis and JaCoCo configuration:
[docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md) under "Code Quality Enforcement".

## Logging

kotlin-logging (SLF4J wrapper) with Logback backend:

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}
logger.debug { "Message with $variable" }  // Lambda-based lazy evaluation
```

Configuration and runtime log-level override: [docs/KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md)
under "Logging Configuration".

## Continuous Integration

GitHub Actions (`.github/workflows/gradle-java21.yml`) runs on push/PR to main/develop:
Java 21 compile, tests, JAR packaging (90-day artifact retention), dependency caching.
CI never runs `heavyTest`, `aiSweep`, or detekt. Build status:
[GitHub Actions](https://github.com/bedaHovorka/interlockSim/actions)

## Documentation

**Thesis:** LaTeX sources in `text/`, build with `docker compose up text`
(outputs to `artifacts/text/bakalarka.pdf`).
**JavaDoc:** `./gradlew javadoc` (outputs to `build/docs/javadoc/`).

Key documents in `docs/` (the directory holds ~75 files; start with these):

- [KOTLIN_STYLE_GUIDE.md](docs/KOTLIN_STYLE_GUIDE.md) - coding conventions, DI patterns, build environment
- [PATH_RESERVATION_ARCHITECTURE.md](docs/PATH_RESERVATION_ARCHITECTURE.md) and
  [PATH_DISCOVERY_ARCHITECTURE.md](docs/PATH_DISCOVERY_ARCHITECTURE.md) - navigation and reservation design
- [CONTEXT_REFACTORING_DESIGN.md](docs/CONTEXT_REFACTORING_DESIGN.md) - context system design and history
- [ANIMATION_ARCHITECTURE.md](docs/ANIMATION_ARCHITECTURE.md) - animated GUI architecture
- [INTERLOCKING_SCOPE_LIMITATIONS.md](docs/INTERLOCKING_SCOPE_LIMITATIONS.md) - deliberate interlocking simplifications
- [GOAL_10_SP2C14_RELIABILITY_REPORT.md](docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md) - Goal 10's measured A4 outcome
- [GOAL_10_SP2C15_FRONTIER_DIAGNOSTIC_SETUP.md](docs/GOAL_10_SP2C15_FRONTIER_DIAGNOSTIC_SETUP.md) - Goal 10's next open step
- [CZECH_RAILWAY_TERMINOLOGY.md](docs/CZECH_RAILWAY_TERMINOLOGY.md) - terminology verification and translation guide

## Simulation Engine

The engine is **kDisco** (`cz.ksimulantenbande.kdisco:kdisco-core`, version **0.6.1** from
`gradle.properties`), maintained as a separate project at
https://github.com/bedaHovorka/kdisco. It replaced jDisco entirely on 2026-03-20.

**There is no planned migration to Kalasim** (owner decision, 2026-08-24). Engine work goes
into kDisco itself. [docs/SIMULATION_LIBRARY_DECISION.md](docs/SIMULATION_LIBRARY_DECISION.md),
[docs/SIMULATION_LIBRARY_DECISION_ROUND2.md](docs/SIMULATION_LIBRARY_DECISION_ROUND2.md) and
[docs/jdisco-research.md](docs/jdisco-research.md) record the 2026 library evaluation —
read them as history, not as the current plan.
