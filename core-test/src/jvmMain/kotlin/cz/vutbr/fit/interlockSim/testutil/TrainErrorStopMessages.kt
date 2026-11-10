/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Fragments of the Train errorStop messages shared by several tests
 * 2026
 */
package cz.vutbr.fit.interlockSim.testutil

/*
 * One shared copy of each fragment, in `:core-test` so every module's tests can reach it: when
 * `Train`'s wording changes, these constants and the tests matching on them change together.
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
