# Contributing to Railway Interlocking Simulator

Thank you for your interest in contributing to InterlockSim! This guide will help you understand our development process, CI/CD workflows, and quality standards.

## Table of Contents

1. [Getting Started](#getting-started)
2. [Development Workflow](#development-workflow)
3. [Testing Requirements](#testing-requirements)
4. [CI/CD Pipeline](#cicd-pipeline)
5. [Code Quality Standards](#code-quality-standards)
6. [Pull Request Process](#pull-request-process)
7. [Documentation](#documentation)

## Getting Started

### Prerequisites

- **Java 21 LTS** or later
- **Gradle** (wrapper included)
- **Git**
- **Docker** (optional, for containerized builds)

### Clone and Build

```bash
# Clone repository
git clone https://github.com/bedaHovorka/interlockSim.git
cd interlockSim

# Build and test
./gradlew clean build

# Run simulation
./gradlew runSim
```

For detailed setup instructions, see [README.md](../README.md) and [CLAUDE.md](../CLAUDE.md).

## Development Workflow

### Branch Naming Convention

Use descriptive branch names following these patterns:

- `feature/short-description` - New features
- `fix/short-description` - Bug fixes
- `refactor/short-description` - Code refactoring
- `docs/short-description` - Documentation updates
- `copilot/short-description` - GitHub Copilot agent changes
- `claude/short-description` - Claude AI agent changes

### Commit Messages

Write clear, descriptive commit messages:

```
Short summary (50 chars or less)

More detailed explanation if needed. Wrap at 72 characters.
Explain what changed and why, not how.

Fixes #123
Relates to #456
```

## Testing Requirements

### Test Coverage Standards

**Minimum Requirements:**
- Overall coverage: ≥ 51% (current baseline)
- New code coverage: ≥ 70% (aim for 80%+)
- Critical paths: 100% coverage (simulation physics, train safety)

### Running Tests

```bash
# Unit tests only (excludes integration tests)
./gradlew test

# Integration tests only
./gradlew integrationTest

# All tests
./gradlew test integrationTest

# Generate coverage report
./gradlew test jacocoTestReport

# View coverage report
open build/reports/jacoco/test/html/index.html  # macOS
xdg-open build/reports/jacoco/test/html/index.html  # Linux
```

### Test Organization

- **Unit Tests** - Fast, isolated tests (default with `./gradlew test`)
- **Integration Tests** - End-to-end scenarios (tagged with `@Tag("integration-test")`)

```kotlin
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Test
@Tag("integration-test")
fun myIntegrationTest() {
    // Integration test code
}
```

### Test Coverage Report

After running tests, review the JaCoCo coverage report:

**Location:** `build/reports/jacoco/test/html/index.html`

**Key Metrics:**
- **Instructions:** Bytecode instruction coverage
- **Branches:** Conditional branch coverage
- **Lines:** Source line coverage
- **Methods:** Method coverage

**Target Coverage by Package:**
- `objects.tracks/` - 85% (safety-critical)
- `xml/` - 85% (data integrity)
- `util/` - 75%
- `objects.cells/` - 72%
- `context/` - 70%
- `objects.paths/` - 52%
- `sim/` - 33% (limited by jDisco framework)

## CI/CD Pipeline

### Automated Workflows

Our CI/CD pipeline includes three main workflows:

#### 1. Gradle Build (Java 21)

**File:** `.github/workflows/gradle-java21.yml`

**Triggers:** Push and PR to `main`, `develop`, `feature/**`, `fix/**`, `copilot/**`, `claude/**`

**Steps:**
1. Compile Kotlin and Java sources
2. Run unit tests
3. Run integration tests
4. Verify Koin DI configuration
5. Create JAR artifacts (shadowJar)
6. Upload JAR artifact (90-day retention)
7. Upload test results
8. Generate test report summary
9. Run smoke test (shunting loop, 300 simulated seconds)

**Duration:** ~10-15 minutes

**Artifacts Produced:**
- `interlockSim-jar-{sha}` - Compiled JAR (90 days)
- `test-results-{sha}` - Test results XML (30 days)

#### 2. SonarQube Analysis

**File:** `.github/workflows/sonarqube.yml`

**Triggers:** Push and PR to `main`, `develop`, `feature/**`, `fix/**`, `copilot/**`, `claude/**`

**Steps:**
1. Build and test with coverage
2. Generate JaCoCo coverage report
3. Upload coverage report artifact
4. Generate coverage report summary
5. Run SonarCloud scan (if configured)
6. Display quality gate status

**Duration:** ~15-20 minutes

**Artifacts Produced:**
- `jacoco-coverage-report-{sha}` - Coverage report HTML (30 days)

**SonarCloud:**
- Requires `SONAR_TOKEN` and `SONAR_ORGANIZATION` secrets
- Skips gracefully if not configured
- View results at: https://sonarcloud.io/project/overview?id=bedaHovorka_interlockSim

#### 3. Claude Code Review

**File:** `.github/workflows/claude-code-review.yml`

**Purpose:** Automated code review using Claude AI

**Triggers:** Pull requests

**Features:**
- Automated code quality review
- Architecture consistency checks
- Best practice suggestions

### Verifying CI Status

Before merging a PR, ensure all CI checks pass:

#### Method 1: GitHub Actions Tab

1. Navigate to: https://github.com/bedaHovorka/interlockSim/actions
2. Find your branch/PR workflow runs
3. Verify all workflows show green checkmarks ✓

#### Method 2: PR Status Checks

1. Open your PR on GitHub
2. Scroll to the bottom to see status checks
3. Wait for all checks to complete
4. Verify all checks pass before merging

#### Method 3: GitHub CLI

```bash
# View workflow runs for current branch
gh run list --branch $(git branch --show-current)

# View specific workflow run
gh run view {run-id}

# Watch workflow run in real-time
gh run watch
```

### Downloading CI Artifacts

```bash
# List artifacts for a PR
gh run list --branch your-branch-name

# Download specific artifact
gh run download {run-id} -n interlockSim-jar-{sha}

# Download all artifacts
gh run download {run-id}
```

Or download via GitHub web UI:
1. Go to Actions tab
2. Click on workflow run
3. Scroll to "Artifacts" section
4. Click artifact name to download

### Coverage Report Validation

After CI completes:

1. **Download Coverage Report:**
   - Go to Actions → Workflow Run → Artifacts
   - Download `jacoco-coverage-report-{sha}.zip`
   - Extract and open `index.html`

2. **Verify Coverage Metrics:**
   - Overall coverage ≥ 51% (baseline)
   - No coverage decrease in modified packages
   - New code has adequate coverage (≥70%)

3. **Review Package-Level Coverage:**
   - Check coverage for packages you modified
   - Ensure critical paths have high coverage
   - Add tests for any uncovered branches

## Code Quality Standards

### Kotlin Style

The project uses a **dual-level approach** for Kotlin code quality:

**1. Legacy Code (Java→Kotlin converted):**
- Configuration: `detekt.yml`
- Permissive rules (allows `var`, higher complexity)
- Run: `./gradlew detekt`

**2. New Kotlin Code:**
- Configuration: `detekt-strict.yml`
- Strict rules (enforces `val`, immutability, Kotlin idioms)
- Place code in: `src/main/kotlin/cz/vutbr/fit/interlockSim/new/`
- Run: `./gradlew detektStrict`

**Code Formatting:**
```bash
# Check formatting
./gradlew ktlintCheck

# Auto-format
./gradlew ktlintFormat
```

**Style Rules:**
- **Indentation:** Tabs (width 4) - NOT spaces
- **Max line length:** 120 characters
- **Encoding:** UTF-8
- **Line endings:** LF

### Java Style

Follow `.editorconfig` configuration:
- Tabs for indentation (width 4)
- Max line length: 120
- UTF-8 encoding, LF line endings

### Running All Quality Checks

```bash
# Full quality check
./gradlew clean build detekt ktlintCheck test integrationTest jacocoTestReport
```

## Pull Request Process

### Before Creating a PR

1. **Sync with upstream:**
   ```bash
   git checkout develop
   git pull origin develop
   git checkout your-feature-branch
   git merge develop
   ```

2. **Run all checks locally:**
   ```bash
   ./gradlew clean build test integrationTest
   ./gradlew detekt ktlintCheck
   ./gradlew jacocoTestReport
   ```

3. **Verify smoke test:**
   ```bash
   java -jar build/libs/interlockSim.jar example shuntingLoop 300
   ```

4. **Review coverage report:**
   ```bash
   open build/reports/jacoco/test/html/index.html
   ```

### Creating a PR

1. **Push your branch:**
   ```bash
   git push origin your-feature-branch
   ```

2. **Open PR on GitHub:**
   - Use the [PR template](.github/PULL_REQUEST_TEMPLATE.md) as a guide
   - Fill out all required sections
   - Link related issues

3. **Complete PR Description:**
   - **Summary:** Clear description of changes
   - **Test Coverage:** Before/after metrics, new tests added
   - **CI/CD Status:** Verify all checks pass
   - **Coverage Validation:** Review JaCoCo report
   - **Documentation:** Update relevant docs

### During Review

1. **Monitor CI Status:**
   - Ensure all checks remain green
   - Address any CI failures promptly
   - Re-run failed checks if needed

2. **Respond to Feedback:**
   - Address reviewer comments
   - Make requested changes
   - Re-run tests after changes

3. **Keep PR Updated:**
   - Merge `develop` regularly if PR is long-lived
   - Resolve merge conflicts promptly

### Before Merge

**Final Checklist:**
- [ ] All CI checks passing (Gradle Build, SonarQube, Code Review)
- [ ] Test coverage maintained or improved
- [ ] JaCoCo report reviewed and validates improvements
- [ ] No merge conflicts with target branch
- [ ] All review comments addressed
- [ ] Documentation updated
- [ ] CHANGELOG.md updated (for user-visible changes)

## Documentation

### Documentation Standards

Update documentation when you:
- Add new features → Update README.md
- Change architecture → Update relevant docs in `docs/`
- Modify developer workflows → Update CLAUDE.md
- Add public APIs → Add JavaDoc/KDoc comments

### Key Documentation Files

- **README.md** - User-facing documentation (quick start, features)
- **CLAUDE.md** - Comprehensive developer guide (architecture, build, test)
- **CONTRIBUTING.md** - This file (contribution guidelines)
- **docs/** - Detailed architecture and design docs
- **CHANGELOG.md** - Notable changes and releases

### Writing Good Documentation

- Use clear, concise language
- Include code examples
- Add diagrams for complex concepts
- Keep documentation up-to-date with code
- Follow existing documentation style

## Questions or Help?

- **Issues:** https://github.com/bedaHovorka/interlockSim/issues
- **Discussions:** https://github.com/bedaHovorka/interlockSim/discussions
- **Documentation:** See [CLAUDE.md](../CLAUDE.md) for comprehensive development guide

## License

By contributing to InterlockSim, you agree that your contributions will be licensed under the same terms as the project.

---

**Thank you for contributing to Railway Interlocking Simulator!** 🚂
