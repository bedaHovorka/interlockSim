/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import kotlinx.atomicfu.atomic

/**
 * Generates unique Koin scope ids for [Context] implementations.
 *
 * [DefaultEditingContext] and [DefaultSimulationContext] each need a scope id that is
 * guaranteed unique for the lifetime of the process. Identity hash codes (as used by
 * [Any.hashCode] on objects that don't override it, or [cz.vutbr.fit.interlockSim.util.platformIdentityCode])
 * are **not** unique - they live in a 31-bit space and collisions become likely once tens of
 * thousands of contexts have been created (birthday bound), which throws
 * `ScopeAlreadyCreatedException` (see Issue #757). A monotonic counter guarantees uniqueness
 * regardless of how many contexts are created.
 */
private val nextContextScopeId = atomic(0L)

/**
 * Returns a new, process-wide unique id suitable for use as a Koin `scopeId`.
 */
internal fun nextContextScopeId(): String = nextContextScopeId.incrementAndGet().toString()
