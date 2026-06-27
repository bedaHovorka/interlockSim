#!/usr/bin/env bash
set -uo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c \
    "import json,sys; d=json.load(sys.stdin); print(d.get('tool_input',{}).get('file_path',''))" \
    2>/dev/null || echo "")

[[ "$FILE_PATH" == *.kt ]] || exit 0

if [[ "$FILE_PATH" == */core/* ]]; then
    exec ./gradlew :core:ktlintCheck
else
    exec ./gradlew :desktop-ui:ktlintCheck
fi
