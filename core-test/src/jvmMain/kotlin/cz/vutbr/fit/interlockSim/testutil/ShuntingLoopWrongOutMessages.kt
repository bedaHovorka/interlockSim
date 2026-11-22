/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Fragments of ShuntingLoop's wrong-out diagnostics (the WRONG_OUT INFO lines) shared by several tests
 * 2026
 */
package cz.vutbr.fit.interlockSim.testutil

/*
 * One shared copy of each fragment of the wrong-out diagnostics — `ShuntingLoop`'s `WRONG_OUT:` INFO
 * line and its class A/B verdicts (Issue #1387), and the `Train` report the classification counts —
 * in `:core-test` so every module's tests can reach them: when the wording changes, these constants
 * and the tests matching on them change together. `:core`'s
 * `ShuntingLoopWrongOutFragmentTripwireTest` fails when a constant no longer appears in the scanned
 * sources.
 */

/**
 * Distinctive suffix of `Train`'s journey report ("… ends in wrong out") for a train that left by
 * another exit than its timetable's. `ShuntingLoop`'s classifier counts those reports and
 * `simulation` tests tally them by this suffix; a reword here must not silently zero the counts.
 */
const val WRONG_OUT_TRAIN_REPORT_FRAGMENT = "ends in wrong out"

/**
 * Distinctive fragment of the prefix of `ShuntingLoop`'s wrong-out INFO line
 * ("… WRONG_OUT: Train #7 left by …"), where the wrong-out exit sentence begins.
 */
const val WRONG_OUT_PREFIX_FRAGMENT = "WRONG_OUT: "

/**
 * Distinctive fragment of the core of `ShuntingLoop`'s wrong-out sentence ("… left by N-Vys-1
 * instead of its timetable exit N-Vys-2 …"), the same fact `Train`'s report states as
 * `ends in wrong out`.
 */
const val WRONG_OUT_EXIT_INSTEAD_FRAGMENT = "instead of its timetable exit"

/**
 * Distinctive fragment of `ShuntingLoop`'s class B verdict ("class B (its own exit was offered but
 * not taken)"): the exit was listed as a station-exit candidate in at least one observation.
 */
const val WRONG_OUT_OFFERED_CLAUSE_FRAGMENT = "class B (its own exit was offered but not taken)"

/**
 * Distinctive fragment of `ShuntingLoop`'s class A fallback verdict: every signal candidate toward
 * the own exit was unavailable at the deciding tick while another candidate was picked.
 */
const val WRONG_OUT_FALLBACK_FRAGMENT = "fallback: platform(s)"

/**
 * Distinctive fragment of `ShuntingLoop`'s class A blind verdict: no signal candidate toward the
 * own exit was available or reachable at the deciding tick. Distinct from
 * [WRONG_OUT_FALLBACK_FRAGMENT] so the two verdicts cannot be confused.
 */
const val WRONG_OUT_BLIND_FRAGMENT = "blind: no platform toward its exit was available or reachable"
