# GitHub Copilot Instructions

This file provides guidance to GitHub Copilot when working with code in this repository.

## Project Overview

Railway Interlocking Simulator - A BSc thesis project (2006/2007) from Brno University of Technology that simulates railway interlocking systems with a graphical editor and discrete event simulation engine.

**Language:** Kotlin (migrated from Java in January 2026)
**Build System:** Gradle with Kotlin DSL
**Java Version:** Java 21 LTS minimum

## Quick Start

**Build and test:**
```bash
./gradlew clean build
```

**Run tests:**
```bash
./gradlew test              # Unit tests only
./gradlew integrationTest   # Integration tests
```

**Run editor GUI:**
```bash
./gradlew runEditor
```

**Run simulation:**
```bash
./gradlew runSim
```

## Build System

This project uses **Gradle with Kotlin DSL**. Key files:
- `build.gradle.kts` - Build configuration and dependencies
- `settings.gradle.kts` - Project settings
- `gradle.properties` - Version management

### Dependencies

- **jDisco 1.2.0** - Discrete event simulation library (Java 6 compatible)
  - Repository: https://github.com/bedaHovorka/jdisco
  - Published to GitHub Packages
- **JUnit 5.11.4** - Testing framework
- **AssertJ 3.27.6** - Fluent assertions
- **Mockito 5.21.0** - Mocking framework
- **SLF4J 2.0.17** + **Logback 1.5.23** - Logging

## Architecture

### Main Entry Point

`cz.vutbr.fit.interlockSim.Main` - Handles three modes:
- `sim` - Run simulation from XML file
- `edit` - Launch graphical editor
- `example` - Run built-in examples (uses `@Example` annotation)

### Core Components

**Context System:**
- `Context` - Base abstraction for railway network configuration
- `SimulationContext` - Simulation execution context
- Factory pattern for context creation

**Object Model:**
- `objects/tracks/` - Track facilities, blocks, occupants
- `objects/cells/` - Grid-based spatial representation
- `objects/paths/` - Route management

**Simulation Engine:**
- Built on jDisco library (external dependency)
- `sim/` package contains simulation processes

**GUI:**
- Swing-based editor in `gui/` package
- Grid-based canvas for track layout editing

### Package Structure

```
src/main/kotlin/cz/vutbr/fit/interlockSim/
├── Main.kt                    - Application entry point
├── context/                   - Context management and factories
├── gui/                       - Graphical editor (Swing)
├── objects/                   - Domain model (tracks, paths, cells)
├── sim/                       - Simulation scenarios
├── util/                      - Utilities and reporting
└── xml/                       - XML parsing and serialization
```

## CRITICAL: Code Modification Guidelines

**Be extremely conservative when editing code in this codebase.**

### Rules

1. **Do not refactor or "improve" working code** - This is a historical codebase from 2007. Stability > modernization.

2. **Tests must exist before modifications** - Any file being modified MUST be covered by tests first. If tests don't exist, write them before changes.

3. **Minimal changes only** - Make only the specific changes requested, nothing more.

4. **No unsolicited modernization** - Do not update code to modern Kotlin/Java features unless explicitly requested.

5. **jDisco library** - jDisco is maintained separately. Do not modify jDisco code; report issues at https://github.com/bedaHovorka/jdisco

### Conservative Approach Applies To

- Java/Kotlin source code
- Build configuration (unless breaking)
- Architecture and design patterns
- Test structure

## Testing

Comprehensive JUnit 5 test suite with AssertJ assertions in `src/test/kotlin/`.

**Test Organization:**
- **Unit tests** - Run with `./gradlew test` (excludes integration tests)
- **Integration tests** - Tagged with `@Tag("integration-test")`, run with `./gradlew integrationTest`

**Test Coverage:** 242 tests across 14 test classes

**Key test utilities:**
- `MockSimulationContext` - Mock implementation
- `TestContextBuilder` - Fluent builder for test contexts
- `TestTrackBuilder` - Fluent builder for test track layouts
- `TestFixtures` - Shared test data

### Tagging Integration Tests

```kotlin
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Test
@Tag("integration-test")
fun myIntegrationTest() {
    // Test code
}
```

## Code Style

Follows `.editorconfig` configuration:
- **Tabs for indentation** (width 4) - NOT spaces
- Max line length: 120
- UTF-8 encoding, LF line endings

### Kotlin Style

**Two-level approach** for Kotlin code quality:

1. **Legacy (Java→Kotlin converted)** - Permissive rules (`detekt.yml`)
   - Allows legacy patterns (var, complex methods)
   - Run with: `./gradlew detekt`

2. **New Kotlin code** - Strict rules (`detekt-strict.yml`)
   - Enforces modern Kotlin best practices
   - Place in: `src/main/kotlin/cz/vutbr/fit/interlockSim/new/`
   - Run with: `./gradlew detektStrict`

**Formatting:**
```bash
./gradlew ktlintCheck      # Check formatting (respects .editorconfig tabs)
./gradlew ktlintFormat     # Auto-format (preserves tab indentation)
```

**CRITICAL:** Both configurations use **tabs** (not spaces) to match original Java code style.

## Kotlin Migration Context

**Migrated from Java to Kotlin in January 2026** (100% of 94 files)

**Key Facts:**
- Conservative structure-preserving conversion
- 242/242 tests passing (236 pass, 5 skipped @Disabled, 1 property change test)
- No unsolicited refactoring during migration
- jDisco interop (Java 6) fully functional

### Nullability Guidelines

**IMPORTANT:** The original Java code has been carefully tuned for null handling. Some Kotlin null-safety checks can introduce bugs if applied incorrectly.

**Type conversion rules:**
- Java type `X` (no annotation) → Kotlin `X?` (nullable)
- Java type `@NotNull X` → Kotlin `X` (non-nullable)
- When in doubt, prefer nullable types (`X?`) for legacy converted code

**Null checks:**
- The old Java code was designed with specific null-handling patterns
- Adding Kotlin null-safety checks (like `requireNotNull()` or `!!`) can break existing logic
- Use safe call operators (`?.`) and elvis operator (`?:`) instead of forcing non-nullability
- Only use `!!` when absolutely certain the value cannot be null (with clear justification)

**Example:**
```kotlin
// GOOD - Preserves Java null behavior
val track: Track? = getTrack()
val length = track?.length ?: 0.0

// AVOID - May break existing logic
val track: Track = getTrack()!!  // Could crash if Java code expects null handling
```

## XML Configuration

Railway networks defined in XML format:
- Schema: `src/main/resources/cz/vutbr/fit/interlockSim/resource/data.xsd`
- Example: `src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml`
- Elements: RailSwitch, RailSemaphore, InOut (entry/exit points), track connections

## Logging

Uses SLF4J with Logback. Configuration: `src/main/resources/logback.xml`

**Log levels:** TRACE, DEBUG, INFO, WARN, ERROR

**Adding logging to code:**
```kotlin
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(ClassName::class.java)

// In methods:
logger.debug("Debug message with context: {}", variable)
logger.info("Informational message")
logger.error("Error message", exception)
```

## Docker Support

**Dockerfile** provides containerized build and runtime with X11 forwarding for GUI.

**Build:**
```bash
docker compose build app
```

**Run editor:**
```bash
docker compose up app
```

**Run simulation:**
```bash
docker compose run app java -ea -jar interlockSim.jar example shuntingLoop 60
```

## Common Commands

**Build:**
```bash
./gradlew clean build       # Full build with tests and JAR
./gradlew shadowJar         # Create uber JAR
```

**Test:**
```bash
./gradlew test                          # Unit tests only
./gradlew integrationTest               # Integration tests
./gradlew test integrationTest          # All tests
```

**Code Quality:**
```bash
./gradlew detekt                        # Check legacy Kotlin code
./gradlew detektStrict                  # Check new Kotlin code (strict)
./gradlew ktlintCheck                   # Check formatting
./gradlew ktlintFormat                  # Auto-format
./gradlew test jacocoTestReport         # Generate coverage report
```

**Run:**
```bash
./gradlew runEditor                     # Launch GUI editor
./gradlew runSim                        # Run simulation
./gradlew runExample -PexampleName=shuntingLoop -PendTime=300
```

**Manual execution:**
```bash
java -ea -jar build/libs/interlockSim.jar sim [xmlFile]      # Simulation mode
java -ea -jar build/libs/interlockSim.jar edit [xmlFile]     # Editor mode
java -ea -jar build/libs/interlockSim.jar example [name] [time]  # Examples
```

**Note:** Always use `-ea` flag to enable assertions.

## Known Issues

### Critical
None. All critical bugs have been fixed.

### Major
- **DEFERRED-001:** Missing assertion predicates in XMLContextFactoryTest (9 occurrences)
  - Tests work correctly but lack explicit exception type predicates
  - SonarQube rule java:S5833

### Minor
- **SIM-001:** Potential division by zero in Motor calculation (mitigated by guard)
- **SIM-002:** Static train counter not reset between runs (cosmetic)
- **DEFERRED-002:** Integer division precision loss in GUI rendering (3 occurrences)
- **DEFERRED-003:** Doubleton missing equals() override (deprecated class)

### Design Limitations
- **SIM-004:** Hardcoded grid coordinates in ShuntingLoop (works only with vyhybna.xml)
- **SIM-005:** Negative train length allowed (no validation)
- **SIM-006:** Assertion-only validation (always run with `-ea` flag)

See `CLAUDE.md` "Known Bugs and Issues" section for detailed information.

## CI/CD

GitHub Actions workflows in `.github/workflows/`:
- `gradle-java21.yml` - Build, test, package JAR
- `sonarqube.yml` - Code quality analysis
- `claude.yml` - Claude AI integration
- `claude-code-review.yml` - Automated code reviews

## Additional Resources

- **Comprehensive documentation:** See `CLAUDE.md` for detailed information
- **Architecture:** See `STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` for static/dynamic separation pattern
- **Code style guide:** See `KOTLIN_STYLE_GUIDE.md`
- **CI/CD workflows:** See `CICD_WORKFLOW_QUICK_START.md`

## Getting Help

When making changes:
1. Read the relevant sections of `CLAUDE.md` for detailed context
2. Ensure tests exist and pass before modifications
3. Follow the conservative approach - minimal changes only
4. Run tests and code quality checks before committing
5. Always enable assertions with `-ea` flag when running the application

**Remember:** This is a working historical codebase. Stability and preservation are more important than modernization.
