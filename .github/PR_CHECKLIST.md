# PR Checklist for CI and Coverage Verification

This checklist ensures all PRs properly document CI status and test coverage improvements.

## Before Creating a PR

### Local Verification

- [ ] **Build passes locally**
  ```bash
  ./gradlew clean build
  ```

- [ ] **All tests pass**
  ```bash
  ./gradlew test integrationTest
  ```

- [ ] **Generate coverage report**
  ```bash
  ./gradlew jacocoTestReport
  open build/reports/jacoco/test/html/index.html
  ```

- [ ] **Code quality checks pass**
  ```bash
  ./gradlew detekt ktlintCheck
  ```

- [ ] **Smoke test passes**
  ```bash
  java -jar build/libs/interlockSim.jar example shuntingLoop 300
  ```

### Coverage Analysis

- [ ] **Record baseline coverage** (before changes)
  - Overall coverage: ____%
  - Package-specific coverage: ____%
  - Total tests: ___

- [ ] **Calculate coverage delta** (after changes)
  - Overall coverage: ____% (Δ: +___%)
  - Package-specific coverage: ____% (Δ: +___%)
  - Total tests: ___ (Δ: +___)

- [ ] **Verify coverage threshold**
  - Overall coverage ≥ 51% (baseline)
  - New code coverage ≥ 70%
  - No coverage decrease in modified packages

## Creating the PR

### PR Description Must Include

- [ ] **Summary** - Clear description of changes
- [ ] **Related Issues** - Link to issues (e.g., "Fixes #123")
- [ ] **Test Coverage Section** with:
  - [ ] Before/after coverage metrics
  - [ ] Number of new tests added
  - [ ] Coverage by package (for modified packages)
- [ ] **CI/CD Status Section** with:
  - [ ] Gradle Build status checkbox
  - [ ] SonarQube Analysis status checkbox
  - [ ] Code Review status checkbox
  - [ ] Links to workflow runs
- [ ] **Coverage Report Validation** with:
  - [ ] JaCoCo report location
  - [ ] Key metrics verified
  - [ ] Confirmation that improvements are as expected

### Use PR Template

- [ ] Fill out `.github/PULL_REQUEST_TEMPLATE.md` completely
- [ ] Don't skip sections (mark "N/A" if not applicable)
- [ ] Include screenshots if UI changes

## After PR is Created

### Monitor CI Status

- [ ] **Watch GitHub Actions**
  - Go to: https://github.com/bedaHovorka/interlockSim/actions
  - Verify all workflows start
  - Wait for completion (~15-20 minutes)

- [ ] **Verify all checks pass**
  - Gradle Build (Java 21) - ✓ green
  - SonarQube Analysis - ✓ green

- [ ] **Download and review coverage report**
  - Download: `jacoco-coverage-report-{sha}.zip`
  - Extract and open: `index.html`
  - Verify metrics match PR description

### Update PR Description

- [ ] **Add CI status** (once workflows complete)
  - Check all checkboxes in CI/CD Status section
  - Add links to actual workflow runs
  - Include artifact links

- [ ] **Add coverage report link**
  - Link to downloaded coverage report artifact
  - Include screenshot of key metrics (optional but recommended)

- [ ] **Document any CI failures**
  - If checks fail, explain why
  - Document resolution steps
  - Re-run checks after fixes

## Before Requesting Review

### Final Verification

- [ ] **All CI checks are green** ✓
  - No failed workflows
  - No pending checks
  - Artifacts generated successfully

- [ ] **Coverage validated**
  - Downloaded JaCoCo report
  - Reviewed coverage metrics
  - Confirmed improvements as expected

- [ ] **Documentation complete**
  - All sections of PR template filled
  - CI status documented
  - Coverage improvements explained

- [ ] **No merge conflicts**
  - Branch is up-to-date with target
  - Resolved any conflicts

### Request Review

- [ ] Add reviewers
- [ ] Add relevant labels
- [ ] Link to project board (if applicable)
- [ ] Add milestone (if applicable)

## Reviewer Checklist

As a reviewer, verify:

- [ ] **PR description is complete**
  - Test coverage section filled out
  - CI status documented
  - Coverage report validated

- [ ] **All CI checks passed**
  - Visit Actions tab
  - Verify green checkmarks
  - Review test results

- [ ] **Coverage improvements verified**
  - Download coverage report
  - Check metrics match PR description
  - Verify no coverage regressions

- [ ] **Code quality acceptable**
  - Detekt passed
  - Ktlint passed
  - SonarQube quality gate passed (if configured)

## Common Issues and Solutions

### Issue: Coverage Report Not Found

**Solution:**
1. Check that tests ran: `./gradlew test jacocoTestReport`
2. Verify report location: `build/reports/jacoco/test/html/index.html`
3. In CI, download artifact: `jacoco-coverage-report-{sha}`

### Issue: CI Check Failed

**Solution:**
1. Click on failed check for details
2. Review logs for error messages
3. Fix issue and push update
4. Re-run workflow if needed

### Issue: Coverage Decreased

**Solution:**
1. Add tests for new/modified code
2. Verify tests are not skipped
3. Check for accidentally removed tests
4. Re-run coverage report

### Issue: Merge Conflicts

**Solution:**
1. Merge target branch: `git merge develop`
2. Resolve conflicts
3. Re-run tests: `./gradlew test integrationTest`
4. Push updated branch

## Quick Reference

### Key Documents

- **PR Template:** `.github/PULL_REQUEST_TEMPLATE.md`
- **Contributing Guide:** `.github/CONTRIBUTING.md`
- **CI/CD Reference:** `.github/CI_CD_QUICK_REFERENCE.md`
- **Example PR:** `.github/EXAMPLE_PR_DESCRIPTION.md`

### Key Commands

```bash
# Full pre-CI check
./gradlew clean build test integrationTest detekt ktlintCheck jacocoTestReport

# View coverage
open build/reports/jacoco/test/html/index.html

# Run smoke test
java -jar build/libs/interlockSim.jar example shuntingLoop 300

# Check CI status (requires GitHub CLI)
gh run list --branch $(git branch --show-current)
gh run watch
```

### CI Workflow Links

- Gradle Build: https://github.com/bedaHovorka/interlockSim/actions/workflows/gradle-java21.yml
- SonarQube: https://github.com/bedaHovorka/interlockSim/actions/workflows/sonarqube.yml

---

**Remember:** A complete PR description with CI status and coverage validation helps reviewers verify changes quickly and ensures quality standards are met.
