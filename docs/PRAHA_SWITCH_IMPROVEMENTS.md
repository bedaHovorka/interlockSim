# Praha Hlavní Nádraží - Switch Layout Improvements

**Date:** 2026-02-06  
**Issue:** Switch layout improvements for Praha Hlavní Nádraží XML configuration  
**Status:** ✅ Complete

## Overview

This document describes the switch layout improvements implemented for the Praha Hlavní Nádraží (Prague Main Station) test configuration. The improvements address two key operational issues identified by users.

## Problem Statement

### Issue 1: Forced Platform Routing
**Problem:** All north entry points forced trains through the main platform zone, with no bypass option for through trains.

**User Comment:** "switches near inouts allow trains going to another inout not into the center of station"

### Issue 2: Missing Car Train Terminal
**Problem:** No dedicated terminal for car-carrying trains (autovlaky).

**User Comment:** "Na hlavaku chybí terminál autovlaku" (At main station, car train terminal is missing)

## Implemented Solutions

### 1. North Bypass Connection (Y=20) — AS BUILT

Direct north-south bypass route that lets through trains avoid the main platform zone.

**Elements (verified against XML):**
- **N-Bypass InOut** at (2,20), `orientation=false` — north bypass entry point
- **Signal** at (4,20) — bypass entry signal
- **Bypass corridor switches** at Y=20:
  - (11,20) `SIMPLE_RIGHT_TRUE` (SW20)
  - (15,20) `SIMPLE_RIGHT_TRUE` (SW16)
  - (46,20) `SIMPLE_RIGHT_TRUE` (SW71)
  - (51,20) `SIMPLE_RIGHT_FALSE` (SW90)
- **S-Bypass InOut** at (60,20), `orientation=true` — south bypass exit point
- **Track blocks** along the Y=20 corridor and the diverges into adjacent Y rows

**Route:**
```
N-Bypass (2,20) → Signal (4,20) → Y=20 corridor (4 switches) → S-Bypass (60,20)
```

**Benefits:**
- Through trains no longer occupy main platform tracks
- Reduced congestion in platform area
- More realistic station operations

### 2. Car Train Terminal (Y=22) — **DEFERRED**

**Status:** Implementation deferred to future work.

**Planned Design:**
- Dedicated peripheral siding for car-carrying train operations
- S-CarTrain InOut at (60,22) - Car train terminal exit
- Terminal diverge switch connecting from bypass
- Siding tracks with appropriate speed limits

**Rationale for Deferral:**
- Bypass route (Y=20) provides core functionality for through trains
- Car train terminal is a specialized feature for future enhancement
- Current focus is on bypass routing improvements

## Technical Specifications

### Statistics

| Element Type | Before | After | Change |
|--------------|--------|-------|--------|
| InOut        | 10     | 11    | +1     |
| Switches     | 40     | 50    | +10    |
| Signals      | 54     | 37    | -17    |
| Track Blocks | 106    | 117   | +11    |
| Grid         | 70x25  | 70x25 | -      |

*Before values reflect `develop` at the time this PR branched. The signal count
decreased because the hand-tuned bypass topology consolidated several adjacent
signals into the new corridor; switch count grew accordingly.*

### New Track Infrastructure

The bypass improvement adds new track infrastructure along the Y=20 corridor to connect the new north-side entry with the existing bypass route.

**Implementation note:** Exact track-block segmentation, lengths, and speed profiles are defined by the current XML configuration and should be treated as the source of truth.

**Summary:**
- New approach infrastructure for the N-Bypass InOut at (2,20)
- New signal and switch connection at the bypass entrance
- Additional track blocks integrating the new entry into the existing Y=20 bypass route

**Note:** Car train terminal tracks (Y=22) are deferred to future implementation.

## Layout Visualization

### Before Improvements
```
Y=4-16: North Entries → Switch Layers → Platform Zone (9 tracks) → Switch Layers → South Exits
Y=20:   (no north entry) ────────────────────────────────────────────────────→ S-Bypass
Y=22:   (no track)
```

### After Improvements
```
Y=4-16: North Entries → Switch Layers → Platform Zone (9 tracks) → Switch Layers → South Exits
Y=20:   N-Bypass ═════════════════════════════════════════════════════════════► S-Bypass
Y=22:   (Car train terminal DEFERRED for future implementation)
```

## Operational Scenarios

### Scenario 1: Through Train (No Platform Stop)
- **Route:** N-Bypass (2,20) → S-Bypass (60,20)
- **Benefit:** Does not occupy platform tracks; verified navigable by `testPragueBypassRouteNavigable`

### Scenario 2: Platform Train (Station Stop)
- **Route:** N-Lib-1 (2,4) → Platform track → S-Vin-1 (60,4)
- **Unchanged from original**

### Scenario 3: Car Train Terminal — **DEFERRED**
Car train terminal operations are deferred to future implementation. When implemented, car trains will be able to use a dedicated terminal siding at Y=22.

## Validation Results

✅ **XML Structure:** Well-formed and parses successfully  
✅ **Grid Dimensions:** 70x25 (unchanged)  
✅ **InOut Count:** 11 (exceeds minimum of 1)  
✅ **N-Bypass Connectivity:** Verified route to S-Bypass (11-node path)  
✅ **Switch Configurations:** All switches have valid types and segments  
✅ **Signal Placements:** All signals correctly oriented  
✅ **Track Speeds:** Realistic limits (25-40 km/h in throat areas)

**Note:** Car train terminal (Y=22) deferred - removed from current implementation.

## Operational Benefits

1. **Reduced Platform Congestion**
   - Through trains no longer occupy main platform tracks
   - Frees up capacity for trains that need platform access
   - Improves station throughput

2. **Realistic Operations**
   - Matches real Prague Main Station topology
   - Bypass routes common in major stations

3. **Operational Flexibility**
   - Dispatchers have more routing options
   - Can handle mixed traffic (platform + through trains)

4. **Network Reliability**
   - Bypass provides alternative route during platform congestion
   - Multiple routing options increase resilience

**Future Enhancement:** Car train terminal (Y=22) deferred for specialized operations.

## File Location

**XML Configuration:** `core-test/src/commonMain/resources/cz/vutbr/fit/interlockSim/xml/fixtures/praha-hlavni-nadrazi.xml`

## Testing

The improved configuration is used in:
- `XMLContextFactoryTest` - Complex station configuration tests
- XML parsing and validation tests

## References

- Issue: Praha Hlavní Nádraží XML: Minor switch layout improvements
- User feedback: Switch positioning near InOuts, missing car train terminal
- Czech Railway Terminology: `docs/CZECH_RAILWAY_TERMINOLOGY.md`

## History

- **2026-02-06:** Initial implementation
  - Added N-Bypass InOut and bypass route infrastructure
  - Validated XML structure and connectivity
  - Documented improvements
- **2026-04-17:** Rebase onto develop
  - Migrated to core-test/src/commonMain/resources/
  - Updated after multiplatform Resources API migration
- **2026-05-05:** Car train terminal deferred
  - Removed S-CarTrain InOut and terminal track
  - Updated element counts: 11 InOuts, 50 switches, 37 signals, 117 track blocks
  - Marked car train terminal as deferred for future implementation

---

*This configuration represents a simplified model of Praha Hlavní Nádraží for testing purposes. It is not a complete representation of the actual station.*
