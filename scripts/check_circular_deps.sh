#!/bin/bash
# Check for circular dependencies between cells/, paths/, and tracks/ packages
# Part of Issue #91: Refactor circular dependency resolution

echo "Checking for circular dependencies in objects/ package..."
echo ""

# Check cells/ doesn't import from paths/ or tracks/
CELLS_VIOLATIONS=$(grep -r "import cz\.vutbr\.fit\.interlockSim\.objects\.\(paths\|tracks\)" \
  src/main/kotlin/cz/vutbr/fit/interlockSim/objects/cells/ 2>/dev/null | wc -l)

# Check paths/ doesn't import from cells/ or tracks/ (excluding tests)
PATHS_VIOLATIONS=$(grep -r "import cz\.vutbr\.fit\.interlockSim\.objects\.\(cells\|tracks\)" \
  src/main/kotlin/cz/vutbr/fit/interlockSim/objects/paths/ 2>/dev/null | wc -l)

# Check tracks/ doesn't import from cells/ or paths/
TRACKS_VIOLATIONS=$(grep -r "import cz\.vutbr\.fit\.interlockSim\.objects\.\(cells\|paths\)" \
  src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/ 2>/dev/null | wc -l)

# All three packages should only import from core/
TOTAL_VIOLATIONS=$((CELLS_VIOLATIONS + PATHS_VIOLATIONS + TRACKS_VIOLATIONS))

echo "Dependency Analysis:"
echo "  cells/  → paths/ or tracks/: $CELLS_VIOLATIONS violations"
echo "  paths/  → cells/ or tracks/: $PATHS_VIOLATIONS violations"
echo "  tracks/ → cells/ or paths/:  $TRACKS_VIOLATIONS violations"
echo ""

if [ $TOTAL_VIOLATIONS -eq 0 ]; then
  echo "✓ No circular dependencies detected"
  echo "  All three packages correctly depend only on core/"
  echo ""
  echo "Expected dependency graph:"
  echo "  core/ ← {cells/, paths/, tracks/}"
  exit 0
else
  echo "✗ Circular dependencies found: $TOTAL_VIOLATIONS violations"
  echo ""

  if [ $CELLS_VIOLATIONS -gt 0 ]; then
    echo "cells/ violations:"
    grep -rn "import cz\.vutbr\.fit\.interlockSim\.objects\.\(paths\|tracks\)" \
      src/main/kotlin/cz/vutbr/fit/interlockSim/objects/cells/ 2>/dev/null
    echo ""
  fi

  if [ $PATHS_VIOLATIONS -gt 0 ]; then
    echo "paths/ violations:"
    grep -rn "import cz\.vutbr\.fit\.interlockSim\.objects\.\(cells\|tracks\)" \
      src/main/kotlin/cz/vutbr/fit/interlockSim/objects/paths/ 2>/dev/null
    echo ""
  fi

  if [ $TRACKS_VIOLATIONS -gt 0 ]; then
    echo "tracks/ violations:"
    grep -rn "import cz\.vutbr\.fit\.interlockSim\.objects\.\(cells\|paths\)" \
      src/main/kotlin/cz/vutbr/fit/interlockSim/objects/tracks/ 2>/dev/null
    echo ""
  fi

  exit 1
fi
