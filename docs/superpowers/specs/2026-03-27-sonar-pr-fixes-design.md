# Design: Fix SonarCloud Failures on Open PRs

**Date:** 2026-03-27
**Status:** Approved
**Scope:** PRs #347, #357, #390, #394 in bedaHovorka/interlockSim

---

## Problem

Four open PRs fail the SonarCloud quality gate. Three conditions block them:

| Condition | Threshold | Actual |
|-----------|-----------|--------|
| `new_reliability_rating` | A (1) | B (2) — 1 open bug |
| `new_coverage` | 80% | 53.2% |
| `new_security_hotspots_reviewed` | 100% | 0% |

PR #355 passes (has sufficient test coverage for its new code). PR #347 fails despite only changing XML/docs — the shared bug and hotspot cause it.

---

## Shared Issues (affect all 4 failing PRs)

### Bug — Doubleton.kt:117 (S2097)

`equals` override does not perform a type check before casting `other`.

**Fix:** Add `if (other !is Doubleton<*, *>) return false` guard before the cast on line 117.

### Security Hotspot — missing `verification-metadata.xml` (S6474)

Gradle dependency verification file is absent. Adding it would require listing checksums for all dependencies (high maintenance burden).

**Resolution:** Mark as "Won't Fix" via SonarCloud UI/API. This is a LOW-probability hotspot about an optional Gradle feature.

---

## Per-PR Fixes

### PR #347 — Add bypass routing to Praha XML
- **Changed files:** XML fixture + docs (no Kotlin)
- **Fix:** Apply Doubleton bug fix; dismiss hotspot
- **Tests needed:** None (no coverable new code)

### PR #394 — Throttle TrainReporter to 1 Hz
- **Changed files:** `core/.../sim/Train.kt` only
- **New code:** `TrainReporter` refactored from `Continuous` to `Process` with `while(true)/hold(1.0)`
- **Fix:** Apply Doubleton bug fix; add unit tests covering:
  - `TrainReporter.start()` → process activates
  - `TrainReporter.terminate()` → unblocks mid-hold and exits
  - Reporting fires at ~1 Hz (not at every ODE step)
- **Coverage target:** ≥ 84% of new lines in Train.kt diff

### PR #357 — Add GUI pre-save validation for InOut count
- **Changed files:** `AbstractPath.kt`, `XMLContextFactory.kt`, `MenuBar.kt`
- **Fix:** Apply Doubleton bug fix; add tests for AbstractPath and XMLContextFactory new logic
- **MenuBar.kt:** Swing GUI (258 lines, 0% coverage, untestable headlessly) — exclude from Sonar analysis via `sonar.exclusions` in `build.gradle.kts`
- **Coverage target:** ≥ 84% of new lines in non-GUI files

### PR #390 — Fix cycle detection + XML attribute persistence
- **Changed files:** `PathReservationRegistry.kt`, `XmlContextReader.kt`, `XmlContextWriter.kt`, `XmlSchemaContent.kt`; tests already added
- **Fix:** Apply Doubleton bug fix; run coverage gap analysis on the diff; add targeted tests for uncovered branches in PathReservationRegistry and XML roundtrip
- **Coverage target:** ≥ 84% of new lines across changed files

---

## Workflow Per Branch

```
git checkout <branch>
git pull                          # sync any remote commits first

# 1. Apply Doubleton fix
# 2. Add/extend tests
# 3. Apply MenuBar exclusion if on PR #357

./gradlew clean test integrationTest        # must pass with zero failures
./gradlew jacocoTestReport                  # verify coverage ≥ 84% (or ≥ 80% fallback)
docker compose run sonar                    # verify all 3 quality gate conditions green

git pull                                    # sync again if remote landed commits during work
git push
```

---

## Acceptance Criteria

Per PR:
- [ ] `git pull` performed before starting work
- [ ] `./gradlew test integrationTest` — zero failures
- [ ] `./gradlew jacocoTestReport` — new code coverage ≥ 84% (≥ 80% fallback)
- [ ] Sonar-in-docker: reliability A, coverage ≥ threshold, hotspot reviewed/dismissed
- [ ] `git pull` before final push; then `git push` succeeds

Project-wide:
- [ ] All 4 PRs show **SonarCloud: SUCCESS** in GitHub

---

## Order of Execution

1. PR #347 (easiest — no tests, just bug fix + hotspot)
2. PR #394 (1 file, focused test addition)
3. PR #357 (3 files, Swing exclusion + targeted tests)
4. PR #390 (most complex, coverage gap analysis needed)

---

## Out of Scope

- Overall project coverage (currently 53%) — only new-code coverage per PR is targeted
- PR #355 — already passing, no changes needed
- Merging any PR — user handles merge after external review
