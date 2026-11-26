# Praha hl.n. peak-hour timetable (Goal 9B SP13.1)

Issue #1489, a subproject leaf of #1447 (Goal 9B SP13, the traffic ramp). Written by the railway-civil-engineer on
2026-11-26. This document goes with the test data `PrahaPeakTimetable` (`:core-test`, `commonMain`) and its data test
`PrahaPeakTimetableDataTest` (`:core`, `commonTest`, JVM and `linuxX64`).

Owner ruling R11 asks for Praha hl.n. with a real peak-hour timetable. The model has 9 station tracks; the real station has
22. So this timetable is **derived from the public timetable and scaled by the track-count ratio**, and it says plainly
what was thinned (R12). Every figure that is not read from a source carries the word *estimate*.

## 1. Public facts (sourced)

| Fact | Value | Source |
|---|---|---|
| Station tracks | 17 through + 5 terminal = 22 | cs.wikipedia, *Praha hlavní nádraží* (infobox) |
| Platforms | 9 platforms with 16 platform edges | same |
| Trains dispatched in 2019 | 286,192 (infobox wording: "vypravené vlaky", trains dispatched) | same |
| Trains per day | about 784 (286,192 / 365, derived) | derived |
| Through S-lines | S7 (Český Brod – Praha hl.n. – Beroun) and S9 (Lysá n. L./Milovice – Praha hl.n. – Benešov) | same |
| S9 departures towards Strančice, weekday 07:00–08:00 | 07:01, 07:21, 07:31, 07:51 | PID timetable booklet 221 (valid 2025-12-14 to 2026-12-12) |
| Arrivals from Vršovice, weekday 07:00–08:00 | 07:05, 07:25, 07:38, 07:40, 07:55 (mixed S9/R; line attribution estimate) | same |
| Hourly R/Sp towards Benešov | departures at :46 (line attribution estimate) | same |

No source was found for the peak-hour train count or for the trains present at once; those stay estimates (section 3).

**National timetable open data (CZPTT XML).** Správa železnic publishes the national timetable as CZPTT XML at
`portal.cisjr.cz/pub/draha/celostatni/szdc/` (yearly `JR2026.zip`, monthly folders). The format description (version
1.09.06, section 5.2) carries the station tracks of arrival and departure in `LocationSubsidiaryCode`, with the note that no
generally valid track identifiers exist yet. **Licence: none found** — the portal listing has no licence text, and a FOSDEM
2026 talk calls the railway manager "not open-data friendly" (the publication is required by law). **No CZPTT data was
downloaded** for this timetable, and whether the Praha hl.n. track entries are filled is not verified. The owner or the
kotlin-tech-lead should confirm the reuse terms before any CZPTT-derived data is committed. The list below is derived from
the PID booklet times and from estimates only.

## 2. Line-to-InOut mapping

The model InOuts are those of `praha-hlavni-nadrazi-station.xml` (the network of the Praha examples). Their names are
model conventions; Vinohrady and Vršovice are district names, not line names.

| InOut | Real counterpart | Lines in this timetable |
|---|---|---|
| `N-Lib-1`, `N-Lib-2` | Nové spojení, northern Vítkov tunnel, towards Libeň (Kolín corridor) | S7 (Český Brod), R9, R10, R18, R19, R41, R49 (through; origin on the Libeň side is an estimate) |
| `N-Vys-1`, `N-Vys-2` | Nové spojení, southern Vítkov tunnel, towards Holešovice (Kralupy, Děčín) and Vysočany (Lysá) | S9, S2, R20, R21, EC via Děčín |
| `N-Bypass` | no real counterpart verified (the old single-track Vítkov tunnel is the only candidate, its use is not confirmed) — **marked fictional, unused** | none |
| `S-Vin-1`, `S-Vin-2` | Vinohrady tunnel I, towards Smíchov (Beroun, Plzeň) | S7 (Beroun), R16, R26 |
| `S-Vrs-1`, `S-Vrs-2`, `S-Vrs-3` | Vinohrady tunnels II and III, towards Vršovice (Strančice, Benešov) | S9, S8, R17, R49 |
| `S-Bypass` | no real counterpart — **fictional, unused** | none |

**Correction to the issue text.** The issue said that every southern line leaves through the three Vinohrady tunnel tubes
and the lines split only at Praha-Vršovice. The source (cs.wikipedia, *Vinohradské tunely*) says otherwise: tunnel I carries
the line towards **Smíchov**, tunnels II and III carry the České Budějovice line towards **Vršovice**. So the split is at the
south portals, and `S-Vin-1/2` (two model InOuts for one real tube) stand for tunnel I, `S-Vrs-1..3` for tunnels II/III.
The split at the model's own south throat is a model abstraction. Whether S8 (Vrané, Čerčany) runs via Vršovice is a weak
source reading (the PID booklet lists it with the Vršovice times); it is mapped to `S-Vrs`.

## 3. Estimates and the scaling

| Quantity | Real (estimate) | Scaled, × 9/22 ≈ 0.41 (estimate) | In the data |
|---|---|---|---|
| Trains in the peak hour 07:00–08:00 | about 50–65 (estimate: 7–8 % of the 784 daily trains; about 59 at 7.5 %) | 20–27 (estimate) | 24 |
| Trains present at once | 12–18 (estimate, issue #1489; basis: about 59 trains per hour times a mean stay of 12–18 min, domain practice) | 5–7 (estimate) | peak 7; mean about 5 (sum of dwell times, 293 min, / 60 min) |
| Terminating share | 40–60 % (estimate, low confidence; no published figure) | — | 14 of 24 = 58 % |
| Dwell, through regional | 1–3 min (estimate, domain practice; issue #1489) | — | 1–3 min |
| Dwell, through long-distance or R | 3–10 min (estimate, domain practice; issue #1489: longer stops for boarding and a crew change) | — | 4 min |
| Dwell, terminating turnaround | 15–60 min (estimate, domain practice; issue #1489: about 15 min for a push-pull set or unit, up to an hour for a long-distance set with cleaning) | — | 15–25 min |
| Reuse gap of one platform track | 2–3 min reoccupation time (estimate, domain practice) | — | at least 3 min (`PrahaPeakTimetable.MIN_TRACK_REUSE_GAP_MINUTES`) |

The target train count and the presence range are **scaled by the track-count ratio**. The data test checks the count
against `PrahaPeakTimetable.TARGET_TRAINS_PER_HOUR_MIN`/`TARGET_TRAINS_PER_HOUR_MAX` (20–27) and the peak presence against
`TARGET_PRESENT_AT_ONCE_MIN`/`TARGET_PRESENT_AT_ONCE_MAX` (5–7) and the topology capacity (`Station.topologyCapacity`, 9).

## 4. The train list

Times are minutes after 07:00: "Entry" is the time at the entry InOut (`PlannedTrain.arrivalMinute`), "Dep." the planned
departure from the station track (`PlannedTrain.departureMinute`, entry plus dwell). The last column is
`PlannedTrain.timeBasis`:

- **sourced arrival** — the entry time is an arrival at Praha hl.n. from Vršovice read from PID booklet 221 (#2, #4, #7, #11,
  #12; for #11 and #12 the line attribution is an estimate);
- **sourced departure; entry time estimate** — PID booklet 221 gives the departure from Praha hl.n. southbound (the S9
  departures 07:01, 07:21, 07:31, 07:51 and the :46 R49). That sourced time is the "Dep." column; the entry time is that
  departure minus the dwell, an estimate (#1, #3, #5, #6, #10). The run-in time from the InOut to the platform is not
  modelled. For #1 the dwell is 1 min so that its entry falls inside the window;
- **estimate** — not read from a timetable: a clock-face offset chosen to spread the arrivals.

Planned station tracks are the names `Station` derives for the platform tracks of the Station variant, `k1`–`k8` from north
to south (y = 4 … 18); `k9`, the bypass station track (y = 20), is unused. Categories: Os/Sp, R, Ex/EC.

| # | Entry | Dep. | Line | Cat. | Kind | Entry InOut | Exit InOut | Dwell | Track | Time |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 07:00 | 07:01 | S9 | Os | through | N-Vys-1 | S-Vrs-1 | 1 | k5 | sourced departure; entry time estimate |
| 2 | 07:05 | 07:07 | S9 | Os | through | S-Vrs-1 | N-Vys-1 | 2 | k5 | sourced arrival |
| 3 | 07:19 | 07:21 | S9 | Os | through | N-Vys-1 | S-Vrs-2 | 2 | k5 | sourced departure; entry time estimate |
| 4 | 07:25 | 07:27 | S9 | Os | through | S-Vrs-2 | N-Vys-1 | 2 | k5 | sourced arrival |
| 5 | 07:29 | 07:31 | S9 | Os | through | N-Vys-2 | S-Vrs-1 | 2 | k7 | sourced departure; entry time estimate |
| 6 | 07:49 | 07:51 | S9 | Os | through | N-Vys-1 | S-Vrs-2 | 2 | k6 | sourced departure; entry time estimate |
| 7 | 07:55 | 07:57 | S9 | Os | through | S-Vrs-1 | N-Vys-2 | 2 | k8 | sourced arrival |
| 8 | 07:12 | 07:15 | S7 | Os | through | S-Vin-2 | N-Lib-2 | 3 | k4 | estimate |
| 9 | 07:42 | 07:45 | S7 | Os | through | N-Lib-1 | S-Vin-1 | 3 | k1 | estimate |
| 10 | 07:42 | 07:46 | R49 | R | through | N-Lib-2 | S-Vrs-3 | 4 | k4 | sourced departure; entry time estimate |
| 11 | 07:38 | 07:58 | R49 | R | turnaround | S-Vrs-3 | N-Vys-1 | 20 | k5 | sourced arrival |
| 12 | 07:40 | 07:55 | S8 | Os | turnaround | S-Vrs-2 | N-Vys-2 | 15 | k7 | sourced arrival |
| 13 | 07:10 | 07:30 | R16 | R | turnaround | S-Vin-1 | N-Lib-2 | 20 | k3 | estimate |
| 14 | 07:48 | 08:08 | R26 | R | turnaround | S-Vin-2 | N-Lib-1 | 20 | k1 | estimate |
| 15 | 07:18 | 07:38 | R17 | R | turnaround | S-Vrs-1 | N-Lib-2 | 20 | k4 | estimate |
| 16 | 07:02 | 07:22 | R20 | R | turnaround | N-Vys-1 | S-Vrs-3 | 20 | k6 | estimate |
| 17 | 07:22 | 07:42 | EC | Ex/EC | turnaround | N-Vys-2 | S-Vrs-2 | 20 | k8 | estimate |
| 18 | 07:13 | 07:38 | R19 | R | turnaround | N-Lib-1 | S-Vin-1 | 25 | k1 | estimate |
| 19 | 07:35 | 08:00 | R18 | R | turnaround | N-Lib-2 | S-Vin-1 | 25 | k3 | estimate |
| 20 | 07:48 | 08:08 | R10 | R | turnaround | N-Lib-1 | S-Vin-1 | 20 | k2 | estimate |
| 21 | 07:28 | 07:43 | R9 | R | turnaround | N-Lib-1 | S-Vin-1 | 15 | k2 | estimate |
| 22 | 07:08 | 07:23 | S2 | Os | turnaround | N-Vys-2 | S-Vrs-1 | 15 | k7 | estimate |
| 23 | 07:55 | 08:10 | R41 | R | turnaround | N-Lib-2 | S-Vin-2 | 15 | k4 | estimate |
| 24 | 07:26 | 07:46 | R21 | R | turnaround | N-Vys-1 | S-Vrs-3 | 20 | k6 | estimate |

Entries per InOut: `N-Lib-1` 4, `N-Lib-2` 3, `N-Vys-1` 5, `N-Vys-2` 3, `S-Vin-1` 1, `S-Vin-2` 2, `S-Vrs-1` 3, `S-Vrs-2` 2,
`S-Vrs-3` 1; the bypass InOuts 0. **15 trains enter from the north and 9 from the south** (see section 5).

### Switch legality and the changes it forced

The rule behind `ShuntingLoop.switchLegalPairsOnly` is `SwitchLegalPairs.accepts`, a
`RouteFinder.isRouteAvailable` check. On the Station variant it accepts 15 north-to-south and 15 south-to-north pairs (pinned
by `PrahaGeneratedPairsTest`). Which station tracks lie on a legal route of each group:

| North | South | Station tracks on a legal route |
|---|---|---|
| `N-Lib-1` | `S-Vin-1/2` | k1, k2 |
| `N-Lib-2` | `S-Vin-1/2` | k3, k4 |
| `N-Lib-2` | `S-Vrs-1..3` | k4 only |
| `N-Vys-1` | `S-Vrs-1..3` | k5, k6 |
| `N-Vys-2` | `S-Vrs-1..3` | k7, k8 |
| `N-Vys-2` | `S-Bypass` | k8, k9 |
| `N-Bypass` | `S-Bypass` | k9 |

Every other pair has no switch-legal route; in particular **no `N-Vys` ↔ `S-Vin` pair and no `N-Lib-1` ↔ `S-Vrs` pair is
legal**. The research draft (placeholder tracks T1–T8) had seven such illegal pairs; the list was changed as follows, keeping
the line family and the side of the real arrival:

- #8 and #9 (S7 Beroun – Český Brod): north end `N-Vys` → `N-Lib`. This also matches the mapping, since the Kolín corridor
  towards Český Brod runs via Libeň.
- #10 (R49 through): entry `N-Lib-1` → `N-Lib-2`, the only Libeň InOut with a route to `S-Vrs`.
- #12 (S8): exit `N-Lib-1` → `N-Vys-2`; #13 (R16): exit `N-Vys-1` → `N-Lib-2`; #14 (R26): exit `N-Vys-2` → `N-Lib-1`.
- #23 (R41/R42 Kolín): exit `S-Vrs-2` → `S-Vin-2`, entry `N-Lib-1` → `N-Lib-2` (track capacity).

Further changes, not forced by legality: #18 R19, #19 R18 and #20 R10 moved from `N-Vys` to `N-Lib` (Kolín corridor via
Libeň, as the issue's mapping states); #22 became S2 via Vysočany and #24 became R21 Mladá Boleslav via Vysočany (estimate),
so the Libeň side is not overloaded; #11's exit is `N-Vys-1` (the only `N-Lib-2` track to `S-Vrs`, k4, is taken by #10).
Which of the InOuts of one tunnel group a train uses (`N-Vys-1` or `N-Vys-2`, `S-Vrs-1`, `-2` or `-3`) is a model choice,
made by the free station track. To fit eight usable station tracks with the reuse gap of section 3, some turnaround dwell
times were shortened inside the 15–60 min range (EC 40 → 20, R16 and R26 25 → 20, R17 30 → 20, R18 and R19 30 → 25, R9
25 → 15) and four estimated times moved by a few minutes (R19 07:15 → 07:13, R17 07:20 → 07:18, R26 07:45 → 07:48, R21
07:32 → 07:26); no sourced time moved. The placeholder tracks T1–T8 were replaced by the real `Station` names `k1`–`k8`,
each chosen from the legal-route tracks of its pair. The track table above comes from enumerating `RouteFinder.findRoutes`
for all 60 cross pairs of the Station variant (2026-11-26); the data test checks only the tracks the list uses.

What the data test checks (`PrahaPeakTimetableDataTest`): every pair is opposite-sided and switch-legal; no train uses a
bypass InOut; every planned track is a `Station.platformTracks` name **and** lies on a switch-legal route of the pair; a
train arrives on a planned track at least `MIN_TRACK_REUSE_GAP_MINUTES` (3 min) after the previous one left it; the sourced
departures are the booklet's southbound departures (departure-sourced trains enter from the north and leave towards
Vršovice) and the sourced arrivals the booklet's arrivals from Vršovice; the train count is 24 and inside 20–27; the peak presence is inside 5–7 and at
most the topology capacity; dwell times follow section 3; the turnaround count is 14; `PrahaPeakTimetable.trainSpecs` gives
one `MultiTrainLoop.TrainSpec` per train in arrival order.

## 5. Simplifications, stated plainly

- One train length, 40 m (`PrahaPeakTimetable.TRAIN_LENGTH`).
- No locomotive change; no empty-stock moves to the depot or stabling sidings.
- The three Vinohrady tunnel tubes and the two Vítkov tunnels are plain InOuts; there is no open-line occupancy behind an
  InOut (an InOut is an open line end).
- **Direction imbalance:** 15 trains enter from the north and 9 from the south. This is a by-product of the
  legality-forced swaps and of the line mapping (most turnarounds of the list arrive from the Kolín corridor and from
  Vysočany/Holešovice), not a sourced figure; the real balance at the station was not measured.
- One train arriving on a track at least 3 min after the previous one left it (reoccupation time, estimate). The departure
  counts the dwell from the entry InOut, so in a run the platform frees later by the run-in time; the gap covers that.
- The hour starts with an empty station: trains that arrived before 07:00 and still stand at 07:00 are not modelled.
- Real platform allocation is not in any data read; the planned tracks are assigned by line family and legal route.
- **Turnarounds.** A real terminating train arrives and later leaves from the same side, often reversing as a push-pull set
  or unit. That is a train reversing direction, not *posun*. `ShuntingLoop` pairs only opposite sides, so each turnaround
  becomes a through train with an exit on the opposite side and keeps its planned station track and its turnaround dwell.
  **Share: 14 of 24 trains (58 %)** are such modelled turnarounds (`PlannedTrain.kind` = `TURNAROUND`), which understates
  the use of the arrival-side throat. Real turnarounds are #1495.

## 6. Arrival disturbance (the seed ruling of #1447)

The timetable is fixed: the train list, planned times, InOut pairs, planned station tracks and categories never change with
the seed. A traffic seed varies only the lateness and bunching at the entry InOut. Ranges of the #1447 ruling
(railway-civil-engineer, 2026-11-26):

| Arm | Disturbance | Status |
|---|---|---|
| Normal | about 80–90 % of trains 0–3 min late, the rest 3–10 min; early arrival at most 1 min | estimate (domain practice) |
| Late and bunched | about 20–30 % of trains 5–20 min late, a few up to 30 min; on one entry line two or three trains arriving at the line's minimum headway, about 2–4 min, instead of their planned spacing | estimate |

A late train never leaves before its planned departure; dwell rules come from section 3.

**Check of the normal range against a public figure.** České dráhy, first half of 2023: **88.8 %** of trains on time, where
on time means a delay of at most 5 minutes (1,093,002 of 1,217,296 trains; network-wide, not split by train type; source:
https://cc.cz/vcas-jezdi-devet-z-deseti-nasich-spoju-hlasi-ceske-drahy-v-presnosti-jsou-pry-na-spicce-evropy/). With the late tail spread evenly over 3–10 min
(estimate), the normal range puts 0.80 + 0.20 × 2/7 ≈ 86 % to 0.90 + 0.10 × 2/7 ≈ 93 % of arrivals within 5 minutes. The ČD
figure lies inside that band, so the normal range is consistent with it. No Správa železnic station-level figure was found.

**Requirements for #1447** (this leaf does not implement the draws):

1. The disturbance draws run on the simulation thread, so one seed gives one event list.
2. A no-disturbance setting reproduces this timetable exactly.
3. Every seed still passes the Goal 1B seed rule on the control arm: an accepted seed has every train entered and every
   train exited, and blind class A = 0 (`goal9b-demands/SP10-praha-llm-dispatcher.md`, narrowed seed rule of the
   traffic-simulation-expert, 2026-11-17).

## 7. Who writes what, and what is not here

The test data and this document are the railway-civil-engineer's (format agreed as a typed Kotlin list reusing
`MultiTrainLoop.TrainSpec`). Production code does not change: no code outside tests reads `PrahaPeakTimetable` yet.
Timetable-driven arrivals in place of `ExampleRegistry.prahaTraffic`, the dwell on the planned track, and the disturbance
draws are separate developer work named in #1447. Running the timetable (the #1447 control arm) and any `sim/` change are
out of scope.

## Sources

- cs.wikipedia: *Praha hlavní nádraží*, *Nové spojení*, *Vinohradské tunely* (read 2026-11-26).
- PID timetable booklet 221, valid 2025-12-14 to 2026-12-12 (pid.cz, `221_jr2026.pdf`); PID rail line list (pid.cz).
- Správa železnic, CZPTT portal `portal.cisjr.cz/pub/draha/celostatni/szdc/` and its format description v1.09.06.
- FOSDEM 2026, D. Koňařík, *Czech transport data* (slides).
- cc.cz, ČD punctuality H1 2023 (88.8 % within 5 minutes).
- Model: `praha-hlavni-nadrazi-station.xml`, `Station`, `SwitchLegalPairs`, `PrahaGeneratedPairsTest`;
  `docs/CZECH_RAILWAY_TERMINOLOGY.md` (InOut names).
