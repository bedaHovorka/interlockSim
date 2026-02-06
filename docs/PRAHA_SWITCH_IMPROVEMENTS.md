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

### 1. North Bypass Connection (Y=20)

Added direct north-south bypass route that allows through trains to avoid the main platform zone.

**New Elements:**
- **N-Bypass InOut** at (2,20) - North bypass entry point
- **Signal** at (4,20) - Bypass entry signal
- **Switch** at (6,20) Type=SIMPLE_LEFT_TRUE - Bypass connection switch
- **Track blocks** connecting to existing Y=20 bypass track

**Route:**
```
N-Bypass (2,20) → Signal (4,20) → Switch (6,20) → Bypass track → S-Bypass (60,20)
```

**Benefits:**
- Through trains no longer occupy main platform tracks
- Reduced congestion in platform area
- More realistic station operations
- Route length: ~420m total
- Travel time: ~50 seconds @ 30 km/h average

### 2. Car Train Terminal (Y=22)

Added dedicated peripheral siding for car-carrying train operations.

**New Elements:**
- **S-CarTrain InOut** at (60,22) - Car train terminal exit
- **Switch** at (52,22) Type=SIMPLE_LEFT_TRUE - Terminal diverge switch
- **Diverge track** (52,20)→(52,22) [20m, 25 km/h]
- **Terminal siding** (52,22)→(60,22) [80m, 25 km/h]

**Route:**
```
Bypass (52,20) → Switch (52,22) → Terminal track → S-CarTrain (60,22)
```

**Benefits:**
- Dedicated terminal for car-carrying trains (autovlaky)
- Peripheral location (realistic placement)
- Connected to bypass, not main platform tracks
- Matches real Prague Main Station operations

## Technical Specifications

### Statistics

| Element Type | Before | After | Change |
|--------------|--------|-------|--------|
| InOut        | 10     | 12    | +2     |
| Switches     | 40     | 42    | +2     |
| Signals      | 54     | 55    | +1     |
| Track Blocks | 106    | 111   | +5     |
| Grid         | 70x25  | 70x25  | -      |

### New Track Infrastructure

Total distance added: **380 meters**

**Track Blocks:**
1. (2,20)→(4,20): 150m, 40→40 km/h (North bypass approach)
2. (4,20)→(6,20): 30m, 40→35 km/h (Signal to switch)
3. (6,20)→(16,20): 100m, 35→30 km/h (Switch to platform zone)
4. (52,20)→(52,22): 20m, 25→25 km/h (Terminal diverge)
5. (52,22)→(60,22): 80m, 25→25 km/h (Terminal track)

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
                                                                                      │
Y=22:   (Car train terminal siding) ═════════════════════════════════════════►S-CarTrain
```

## Operational Scenarios

### Scenario 1: Through Train (No Platform Stop)
- **Route:** N-Bypass (2,20) → S-Bypass (60,20)
- **Distance:** ~420m
- **Time:** ~50 seconds @ 30 km/h average
- **Benefit:** Does not occupy platform tracks

### Scenario 2: Platform Train (Station Stop)
- **Route:** N-Lib-1 (2,4) → Platform track → S-Vin-1 (60,4)
- **Distance:** ~500m total
- **Time:** ~2-3 minutes with stop
- **Unchanged from original**

### Scenario 3: Car Train Terminal
- **Route:** N-Bypass (2,20) → Bypass → Switch (52,20) → Terminal → S-CarTrain (60,22)
- **Distance:** ~520m total (420m bypass + 100m terminal)
- **Time:** ~60 seconds @ 25 km/h in terminal
- **Benefit:** Dedicated handling for car-carrying trains

## Validation Results

✅ **XML Structure:** Well-formed and parses successfully  
✅ **Grid Dimensions:** 70x25 (unchanged)  
✅ **InOut Count:** 12 (exceeds minimum of 2)  
✅ **N-Bypass Connectivity:** Verified route to S-Bypass (11-node path)  
✅ **Terminal Connectivity:** Verified route from bypass to S-CarTrain (3-node path)  
✅ **Switch Configurations:** All switches have valid types and segments  
✅ **Signal Placements:** All signals correctly oriented  
✅ **Track Speeds:** Realistic limits (25-40 km/h in throat areas)

## Operational Benefits

1. **Reduced Platform Congestion**
   - Through trains no longer occupy main platform tracks
   - Frees up capacity for trains that need platform access
   - Improves station throughput

2. **Realistic Operations**
   - Matches real Prague Main Station topology
   - Bypass routes common in major stations
   - Car train terminals typically on periphery

3. **Operational Flexibility**
   - Dispatchers have more routing options
   - Can handle mixed traffic (platform + through trains)
   - Terminal supports specialized car train operations

4. **Network Reliability**
   - Bypass provides alternative route during platform congestion
   - Terminal allows long-term parking of car trains
   - Multiple routing options increase resilience

## File Location

**XML Configuration:** `src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/praha-hlavni-nadrazi.xml`

## Testing

The improved configuration is used in:
- `XMLContextFactoryTest` - Complex station configuration tests
- XML parsing and validation tests

## References

- Issue: Praha Hlavní Nádraží XML: Minor switch layout improvements
- User feedback: Switch positioning near InOuts, missing car train terminal
- Czech Railway Terminology: `docs/CZECH_RAILWAY_TERMINOLOGY.md`

## History

- **2026-02-06:** Improvements implemented and validated
  - Added N-Bypass InOut and bypass route infrastructure
  - Added S-CarTrain terminal and siding tracks
  - Validated XML structure and connectivity
  - Documented improvements

---

*This configuration represents a simplified model of Praha Hlavní Nádraží for testing purposes. It is not a complete representation of the actual station.*
