#!/usr/bin/env bash
set -uo pipefail

DATE=$(date '+%Y-%m-%d')
BRANCH=$(git branch --show-current 2>/dev/null || echo "unknown")
COMMITS=$(git log --oneline -5 2>/dev/null || echo "(no commits)")
CHANGED=$(git diff --name-only HEAD~5 HEAD 2>/dev/null || echo "")

count_files() { echo "$CHANGED" | { grep -c "^${1}/" || true; }; }
list_files()  { echo "$CHANGED" | grep "^${1}/" | head -3 | tr '\n' ',' | sed 's/,$//'; }

CORE_COUNT=$(count_files "core")
DUI_COUNT=$(count_files "desktop-ui")
FS_COUNT=$(count_files "fast-sim")
CORE_LIST=$(list_files "core")
DUI_LIST=$(list_files "desktop-ui")
FS_LIST=$(list_files "fast-sim")

fmt_subproject() {
    local label="$1" count="$2" list="$3"
    [[ -n "$list" ]] \
        && echo "- ${label} -- ${count} files (${list})" \
        || echo "- ${label} -- ${count} files"
}

S1=$(fmt_subproject ":core" "$CORE_COUNT" "$CORE_LIST")
S2=$(fmt_subproject ":desktop-ui" "$DUI_COUNT" "$DUI_LIST")
S3=$(fmt_subproject ":fast-sim" "$FS_COUNT" "$FS_LIST")

CONTEXT="## Session Context -- ${DATE}

**Branch:** ${BRANCH}
**Last 5 commits:**
$(echo "$COMMITS" | sed 's/^/- /')

**Recent changes by subproject (last 5 commits):**
${S1}
${S2}
${S3}"

python3 -c "import json,sys; d=sys.stdin.read(); print(json.dumps({'hookSpecificOutput':{'hookEventName':'SessionStart','additionalContext':d}}))" <<< "$CONTEXT"
