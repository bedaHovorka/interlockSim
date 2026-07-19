# Pull Request Description

## Summary

<!-- Provide a brief description of the changes in this PR -->

## Related Issues

<!-- Link to related issues (e.g., Fixes #123, Relates to #456) -->

## Changes Made

<!-- List the key changes in this PR -->

- 
- 
- 

## Test Coverage

### Coverage Improvements

<!-- If this PR improves test coverage, provide details -->

**Before:**
- Coverage: ___%
- Tests: ___ passing / ___ total

**After:**
- Coverage: ___%
- Tests: ___ passing / ___ total

**New Tests Added:**
- [ ] Unit tests: ___ tests
- [ ] Integration tests: ___ tests

### Test Execution

```bash
# Commands used to verify tests
./gradlew test integrationTest
./gradlew jacocoTestReport
```

**Coverage Report Location:** `build/reports/jacoco/test/html/index.html`

## CI/CD Status

### Build Status

<!-- Before submitting, verify all CI checks are passing -->

- [ ] ✓ Gradle Build (Java 21) - [View Workflow](.github/workflows/gradle-java21.yml)
  - [ ] Compilation successful
  - [ ] Unit tests passing
  - [ ] Integration tests passing
  - [ ] Koin DI verification passing
  - [ ] JAR artifact created
  - [ ] Smoke test passed
- [ ] ✓ SonarQube Analysis - [View Workflow](.github/workflows/sonarqube.yml)
  - [ ] Code quality checks passed
  - [ ] JaCoCo coverage report generated
  - [ ] SonarCloud scan completed (if configured)

### How to Verify CI Status

1. **Check Actions Tab:** https://github.com/bedaHovorka/interlockSim/actions
2. **View Build Details:** Click on the workflow run for this PR
3. **Review Artifacts:**
   - JAR: `interlockSim-jar-{sha}`
   - Test Results: `test-results-{sha}`
   - Coverage Report: `jacoco-coverage-report-{sha}`

### Coverage Report Validation

<!-- After CI completes, review the JaCoCo coverage report -->

**Coverage Report URL:** Available in GitHub Actions artifacts after CI completes

**Key Metrics to Check:**
- Overall instruction coverage ≥ 51% (baseline)
- No decrease in coverage for modified packages
- New code has adequate test coverage

## Code Quality

### Linting and Style

- [ ] Detekt checks passed: `./gradlew detekt`
- [ ] Ktlint formatting passed: `./gradlew ktlintCheck`
- [ ] EditorConfig rules followed (tabs, max line length 120)

### Manual Verification

<!-- Describe any manual testing performed -->

**Tested Scenarios:**
- 
- 

**Commands Used:**
```bash
# Example commands for manual testing
./gradlew runSim
./gradlew runEditor
./gradlew runExampleGui
```

## Breaking Changes

<!-- List any breaking changes and migration steps -->

- [ ] No breaking changes
- [ ] Breaking changes (describe below):

## Documentation

<!-- Check all that apply -->

- [ ] README.md updated (if user-facing changes)
- [ ] CLAUDE.md updated (if developer-facing changes)
- [ ] JavaDoc/KDoc comments added/updated
- [ ] Architecture docs updated (if design changes)
- [ ] CHANGELOG.md updated

## Checklist

<!-- Complete before requesting review -->

- [ ] All CI checks are passing (see CI/CD Status above)
- [ ] Test coverage maintained or improved
- [ ] JaCoCo report reviewed and validates expected improvements
- [ ] Code follows project style guidelines (tabs, max line 120)
- [ ] No console warnings or errors during build
- [ ] Smoke test passes locally: `./gradlew clean build && java -jar build/libs/interlockSim.jar example shuntingLoop 1024`
- [ ] Documentation updated as needed
- [ ] Ready for review

## Review Notes

<!-- Any additional notes for reviewers -->
