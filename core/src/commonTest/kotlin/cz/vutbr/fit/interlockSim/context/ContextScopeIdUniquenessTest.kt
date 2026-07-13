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

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Regression tests for Issue #757: [DefaultEditingContext] and [DefaultSimulationContext]
 * used to derive their Koin `scopeId` from an identity hash code (`this.hashCode()` /
 * [cz.vutbr.fit.interlockSim.util.platformIdentityCode]). Identity hash codes live in a
 * 31-bit space, so under high context-creation volume (tens of thousands of contexts, e.g.
 * JMH `AverageTime` benchmarks) two live contexts could collide and Koin would throw
 * `ScopeAlreadyCreatedException`.
 *
 * The fix replaces the identity hash code with a process-wide monotonic counter
 * ([nextContextScopeId]), which is unique by construction regardless of how many contexts
 * are created concurrently.
 */
class ContextScopeIdUniquenessTest {
	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	@Test
	fun scopeIdsAreUniqueAcrossManyEditingContexts() {
		// Enough instances to verify the new counter's uniqueness at a volume where a
		// non-unique scheme (e.g. a naive hash) would already show collisions, without
		// making the test slow. Note: 5,000 is below the ~77,000 birthday-bound 50% point
		// of the old 31-bit identity-hash space, so this guard primarily validates the new
		// counter rather than reproducing the old collision (Issue #757).
		val count = 5_000
		val scopeIds = mutableSetOf<String>()
		val contexts = mutableListOf<DefaultEditingContext>()
		try {
			repeat(count) {
				val context = DefaultEditingContext(2, 2)
				contexts.add(context)
				scopeIds.add(context.scope.id)
			}
			// Every context must have received a distinct scope id: no collisions, and thus
			// no ScopeAlreadyCreatedException would have been thrown while creating them.
			assertThat(scopeIds).hasSize(count)
		} finally {
			contexts.forEach { it.close() }
		}
	}

	@Test
	fun nextContextScopeIdIsMonotonicAndUnique() {
		val ids = (1..1_000).map { nextContextScopeId().toLong() }
		// Unique by construction (atomic incrementAndGet)...
		assertThat(ids.toSet()).hasSize(ids.size)
		// ...and strictly increasing, matching the function's name.
		assertThat(ids.zipWithNext().all { (a, b) -> a < b }).isTrue()
	}
}
