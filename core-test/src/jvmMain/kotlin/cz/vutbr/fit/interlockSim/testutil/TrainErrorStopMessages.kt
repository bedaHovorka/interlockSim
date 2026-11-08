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

/**
 * Distinctive fragment of the mid-journey never-extended-route `errorStop` wording of `Train`
 * ("… no route extension at …"), distinct from the origin wording "no entry route reserved".
 * One shared copy, in `:core-test` so every module's tests can reach it: when `Train`'s wording
 * changes, this constant and the tests matching on it change together.
 */
const val ROUTE_EXTENSION_FRAGMENT = "no route extension"
