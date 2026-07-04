# CI/CD Quick Reference Guide

This guide provides quick reference information for working with the InterlockSim CI/CD pipeline.

## CI Status Badges

Add these badges to your PR description or documentation to show build status:

### Main Build Status

```markdown
[![Gradle Build](https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml/badge.svg)](https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml)
```

### SonarQube Analysis Status

```markdown
[![SonarQube](https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml/badge.svg)](https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml)
```

### All Badges Together

```markdown
[![Gradle Build](https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml/badge.svg)](https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml)
[![SonarQube](https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml/badge.svg)](https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml)
```

## CI Workflow Overview

### 1. Gradle Build (Java 21)

**File:** `.github/workflows/gradle-java21.yml`

| Step | Duration | Description |
|------|----------|-------------|
| Compile | ~3 min | Compiles Kotlin and Java sources |
| Unit Tests | ~5 min | Runs unit tests (excludes integration tests) |
| Integration Tests | ~3 min | Runs integration tests |
| Koin DI Verification | ~1 min | Validates dependency injection configuration |
| Create JAR | ~2 min | Builds shadowJar with all dependencies |
| Smoke Test | ~5 min | Runs shunting loop example (300 time units) |

**Total Duration:** ~15-20 minutes

**Artifacts:**
- `interlockSim-jar-{sha}` - Uber JAR (90-day retention)
- `test-results-{sha}` - JUnit XML reports (30-day retention)
- `sonar-inputs-{sha}` - Compiled classes, test results, JaCoCo XML for SonarQube Analysis (7-day retention)

**Triggers:** Push to any branch

### 2. SonarQube Analysis

**File:** `.github/workflows/sonarqube.yml`

| Step | Duration | Description |
|------|----------|-------------|
| Download inputs | ~1 min | Reuses Gradle Build's compiled classes/test-results/JaCoCo XML — no rebuild or re-test |
| SonarCloud Scan | ~5 min | Code quality analysis (if configured) |

**Total Duration:** ~2-5 minutes

**Triggers:** Automatically after Gradle Build completes successfully (`workflow_run`), or manual dispatch

## Quick Commands

### Local Testing (Before CI)

```bash
# Full pre-CI check (recommended)
./gradlew clean build test integrationTest detekt ktlintCheck jacocoTestReport

# Quick check (no integration tests)
./gradlew clean build test detekt ktlintCheck

# Generate coverage report only
./gradlew test jacocoTestReport

# Run smoke test
java -jar build/libs/interlockSim.jar example shuntingLoop 300
```

### View CI Results

```bash
# List recent workflow runs (requires GitHub CLI)
gh run list --limit 10

# View specific run
gh run view {run-id}

# Watch current run
gh run watch

# View logs
gh run view {run-id} --log

# Download artifacts
gh run download {run-id}
```

### Re-run Failed Workflows

```bash
# Re-run failed jobs only
gh run rerun {run-id} --failed

# Re-run entire workflow
gh run rerun {run-id}
```

## Coverage Report Checklist

After CI completes, verify coverage improvements:

### Download Coverage Report

**Option 1: GitHub Web UI**
1. Go to Actions → Workflow Run
2. Scroll to Artifacts section
3. Download `jacoco-coverage-report-{sha}.zip`
4. Extract and open `index.html`

**Option 2: GitHub CLI**
```bash
gh run list --branch your-branch
gh run download {run-id} -n jacoco-coverage-report-{sha}
```

### Review Coverage Metrics

- [ ] **Overall Coverage:** ≥ 51% (baseline maintained)
- [ ] **Modified Packages:** No coverage decrease
- [ ] **New Code:** ≥ 70% coverage
- [ ] **Critical Paths:** High coverage (trains, safety, physics)

### Coverage by Package (Targets)

| Package | Target | Current |
|---------|--------|---------|
| objects.tracks/ | 85% | 85% ✓ |
| xml/ | 85% | 85% ✓ |
| util/ | 75% | 75% ✓ |
| objects.cells/ | 72% | 72% ✓ |
| context/ | 70% | 70% ✓ |
| objects.paths/ | 52% | 52% ✓ |
| sim/ | 33% | 33% ✓ |

## PR Description Template

Use this template for PRs with test coverage improvements:

````markdown
## Test Coverage Improvements

### Coverage Changes

**Before:**
- Overall Coverage: 51.2%
- Tests: 628 passing / 662 total

**After:**
- Overall Coverage: 53.7% (+2.5%)
- Tests: 645 passing / 679 total (+17 tests)

### New Tests Added

- Unit Tests: 12 tests
  - `TrainPhysicsTest` - Train acceleration and braking (5 tests)
  - `RailSwitchTest` - Switch state transitions (4 tests)
  - `PathReservationTest` - Atomic reservation logic (3 tests)

- Integration Tests: 5 tests
  - `ShuntingLoopIntegrationTest` - End-to-end shunting scenarios (5 tests)

### Coverage by Modified Package

| Package | Before | After | Change |
|---------|--------|-------|--------|
| sim/ | 33.0% | 38.5% | +5.5% ✓ |
| objects.tracks/ | 85.0% | 87.2% | +2.2% ✓ |

### CI Status

- [x] ✓ Gradle Build - All tests passing
- [x] ✓ SonarQube - Coverage report generated
- [x] ✓ Coverage baseline maintained (≥51%)
- [x] ✓ New code coverage ≥70%

### Coverage Report

Coverage report available in CI artifacts: `jacoco-coverage-report-{sha}`

**Key Improvements:**
- Added physics validation tests for Train acceleration
- Improved coverage for RailSwitch edge cases
- Added integration tests for path reservation atomicity
````

## Troubleshooting CI Failures

### Build Failures

**Symptom:** Compilation errors

**Solution:**
```bash
# Ensure clean build locally
./gradlew clean build --warning-mode=all --stacktrace

# Check for syntax errors
./gradlew compileKotlin compileJava --stacktrace
```

### Test Failures

**Symptom:** Tests fail in CI but pass locally

**Solutions:**
- Check for timing-dependent tests
- Verify test isolation (no shared state)
- Check for platform-specific assumptions
- Review CI logs for stack traces

```bash
# Run tests with full output
./gradlew test --info --stacktrace

# Run specific test
./gradlew test --tests "ClassName.testMethod"
```

### Coverage Regression

**Symptom:** Coverage drops below baseline

**Solutions:**
- Add tests for new code
- Verify removed code had equivalent test coverage
- Check if tests were accidentally skipped

```bash
# Generate coverage report
./gradlew test jacocoTestReport

# Review uncovered code
open build/reports/jacoco/test/html/index.html
```

### Smoke Test Failures

**Symptom:** Simulation doesn't complete

**Solutions:**
- Verify simulation logic correctness
- Check for infinite loops
- Review simulation logs

```bash
# Run smoke test locally with verbose logging
java -Dlogback.level=DEBUG -jar build/libs/interlockSim.jar example shuntingLoop 300
```

## Best Practices

### Before Pushing

1. ✓ Run full test suite locally
2. ✓ Generate and review coverage report
3. ✓ Run code quality checks (detekt, ktlint)
4. ✓ Verify smoke test passes
5. ✓ Update documentation if needed

### During PR Review

1. ✓ Monitor CI status (all workflows must pass)
2. ✓ Download and review coverage report
3. ✓ Address reviewer feedback promptly
4. ✓ Keep branch up-to-date with target

### Before Merge

1. ✓ All CI checks green
2. ✓ Coverage maintained/improved
3. ✓ No merge conflicts
4. ✓ All review comments addressed
5. ✓ Documentation updated

## Additional Resources

- **Contributing Guide:** [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md)
- **PR Template:** [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md)
- **Developer Guide:** [CLAUDE.md](../CLAUDE.md)
- **README:** [README.md](../README.md)

---

**Need Help?**
- Open an issue: https://github.com/bedaHovorka/interlockSim/issues
- View workflow runs: https://github.com/bedaHovorka/interlockSim/actions
