/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Fragments of the Train stall diagnostics (errorStop messages and stall WARNs) shared by several tests
 * 2026
 */
package cz.vutbr.fit.interlockSim.testutil

/*
 * One shared copy of each fragment of `Train`'s stall diagnostics — its `errorStop` messages and its
 * stall WARNs — in `:core-test` so every module's tests can reach it: when `Train`'s wording changes,
 * these constants and the tests matching on them change together. `:core`'s
 * `TrainErrorStopFragmentTripwireTest` fails when a constant no longer appears in `Train`'s code.
 */

/**
 * Distinctive fragment of the mid-journey never-extended-route `errorStop` wording of `Train`
 * ("… no route extension at …"), distinct from the origin wording [ORIGIN_ROUTE_EXTENSION_FRAGMENT].
 */
const val ROUTE_EXTENSION_FRAGMENT = "no route extension"

/**
 * Distinctive fragment of the never-admitted-origin `errorStop` wording of `Train`
 * ("… no entry route reserved at InOut …"), distinct from the mid-journey wording
 * [ROUTE_EXTENSION_FRAGMENT].
 */
const val ORIGIN_ROUTE_EXTENSION_FRAGMENT = "no entry route reserved"

/**
 * Distinctive fragment of the origin `NoTopologicalPath` `errorStop` wording of `Train` after its
 * bounded retries ("… No topological path from origin InOut '…' …"), distinct from the mid-journey
 * wording [MID_JOURNEY_NO_PATH_FRAGMENT].
 */
const val ORIGIN_NO_PATH_FRAGMENT = "No topological path from origin InOut"

/**
 * Distinctive fragment of the mid-journey `NoTopologicalPath` `errorStop` wording of `Train` after
 * its bounded retries ("… navigation reports no usable path from '…' …"), distinct from the origin
 * wording [ORIGIN_NO_PATH_FRAGMENT].
 */
const val MID_JOURNEY_NO_PATH_FRAGMENT = "navigation reports no usable path"

/**
 * Distinctive fragment of the mid-journey ownership-conflict stall WARN of `Train` ("… still waiting
 * at '…' for the dispatcher to extend its route after …"), distinct from the origin WARN
 * [ORIGIN_STALL_WARN_FRAGMENT].
 */
const val STALL_WARN_FRAGMENT = "for the dispatcher to extend its route"

/**
 * Distinctive fragment of the origin ownership-conflict stall WARN of `Train` ("… still waiting at
 * entry InOut '…' for the dispatcher to reserve its entry route after …"), distinct from the
 * mid-journey WARN [STALL_WARN_FRAGMENT].
 */
const val ORIGIN_STALL_WARN_FRAGMENT = "for the dispatcher to reserve its entry route"
