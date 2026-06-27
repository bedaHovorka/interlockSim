# Claude Code Hooks Setup

**Date:** 2026-06-27  
**Status:** Implemented

## Problem

Claude was being used as a passive LLM — no automated quality gates, no live project state at session start, no enforcement of existing workflow rules (integrationTest before push).

## Solution

Three hooks in `.claude/settings.json` (committed to git):

| Hook | Trigger | Action | Blocking? |
|------|---------|--------|-----------|
| SessionStart | Every new/cleared session | Inject branch + commits + subproject diff | No |
| PostToolUse | Edit or Write on any `*.kt` file | Run subproject `ktlintCheck`, report violations | No (advisory) |
| PreToolUse | `Bash(git push:*)` | Run `integrationTest` then `docker compose up --build -d` | Yes |

## Files

```
.claude/
├── settings.json            # Hook configuration (committed)
└── hooks/
    ├── session-start.sh     # Outputs branch + commits + subproject diff as JSON context
    └── ktlint-check.sh      # Reads stdin file_path, runs subproject ktlintCheck if *.kt
```

`settings.local.json` remains gitignored (personal allow rules, unchanged).

## Session-Start Output

Injected as `{"context": "..."}` JSON. Example:

```
## Session Context -- 2026-06-27

**Branch:** develop
**Last 5 commits:**
- b81fc6a docs(team): fix stale Koin sim/ restriction
- ...

**Recent changes by subproject (last 5 commits):**
- :core         -- 36 files (core/build.gradle.kts, ...)
- :desktop-ui   -- 54 files (desktop-ui/build.gradle.kts, ...)
- :fast-sim     -- 11 files (fast-sim/build.gradle.kts, ...)
```

## Pre-Push Sequence

```
./gradlew integrationTest && docker compose up --build -d
```

- `integrationTest` fails → push blocked, no Docker build
- Docker build fails → push blocked
- Both pass → push proceeds

Enforces the project rule: integration tests must pass before pushing.

## .gitignore Change

Replaced `.claude/` (blanket ignore) with:

```gitignore
.claude/*
!.claude/settings.json
!.claude/hooks/
```

This keeps `settings.local.json`, `worktrees/`, and legacy `skills/*.json` gitignored while allowing the new hooks to be committed.
