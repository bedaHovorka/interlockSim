# GitHub Copilot Instructions

**Follow [CLAUDE.md](../CLAUDE.md).** It is the single source of truth for this
repository — read it before making any change. This file intentionally repeats
nothing from it, with one deliberate exception: the clean-code trinity below,
stated twice on purpose so it reaches even an agent that never follows the
link. If the two files ever disagree, CLAUDE.md wins.

## Where the rules live

- Repo-wide rules (English-only output, testing policy, code quality, CI):
  [CLAUDE.md](../CLAUDE.md)
- Per-module guidance: [core/CLAUDE.md](../core/CLAUDE.md),
  [core-test/CLAUDE.md](../core-test/CLAUDE.md),
  [dispatcher-agent/CLAUDE.md](../dispatcher-agent/CLAUDE.md),
  [desktop-ui/CLAUDE.md](../desktop-ui/CLAUDE.md),
  [fast-sim/CLAUDE.md](../fast-sim/CLAUDE.md)
- Coding conventions, DI patterns, build environment:
  [docs/KOTLIN_STYLE_GUIDE.md](../docs/KOTLIN_STYLE_GUIDE.md)
- Agent roles and collaboration protocol: [TEAM.md](../TEAM.md)

## The clean-code trinity

**ktlint** (format), **detekt** (code smells), **SonarCloud** (bugs, coverage,
duplication). All three must pass before merge. Disabling ktlint is forbidden.

## Everyday commands

```bash
./gradlew clean build         # Build and test
./gradlew test                # Unit tests
./gradlew integrationTest     # Integration tests
./gradlew detekt ktlintCheck  # Static analysis and format check
```

Everything else (run tasks, heavy tests, Docker, SonarCloud): see CLAUDE.md and
the module guides linked above.

## History note

This file was rewritten on 2026-08-29. Its old copy had drifted far from the
project (wrong simulation library, wrong test libraries, wrong module layout,
nonexistent Gradle tasks). That is why it now defers to CLAUDE.md by link
instead of repeating facts that go stale.