/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ContextSlots
import cz.vutbr.fit.interlockSim.dispatcher.agents.StationCapacity
import cz.vutbr.fit.interlockSim.dispatcher.publish
import cz.vutbr.fit.interlockSim.dispatcher.publishedOrNull
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.module.Module

/**
 * Verifies the per-context [ContextSlots] binding in [dispatcherAgentModule] (Issue #1348).
 *
 * An instance published into one context must never be visible through another context's scope —
 * neither while the first context is open nor after it is closed.
 */
@DisplayName("ContextSlots is scoped per simulation context (#1348)")
class ContextSlotsScopeTest : DispatcherKoinTestBase() {
	override fun getTestModules(): List<Module> = listOf(dispatcherAgentModule, commonCoreTestModule)

	private fun loadContext(): DefaultSimulationContext = TestFixtures.newShuntingSimulationContext().tracked()

	@Test
	@DisplayName("a published instance is resolved from the same context's scope")
	fun publishedInstanceIsResolved() {
		val context = loadContext()
		val capacity = StationCapacity(2)

		context.scope.publish(capacity)

		assertThat(context.scope.publishedOrNull<StationCapacity>()).isSameInstanceAs(capacity)
	}

	@Test
	@DisplayName("an instance published in one context is not visible to another, open or closed")
	fun publishedInstanceDoesNotLeakToAnotherContext() {
		val first = loadContext()
		first.scope.publish(StationCapacity(2))
		val second = loadContext()

		assertThat(second.scope.publishedOrNull<StationCapacity>(), "while the first context is open").isNull()
		first.close()
		val third = loadContext()
		assertThat(third.scope.publishedOrNull<StationCapacity>(), "after the first context is closed").isNull()
	}

	@Test
	@DisplayName("a closed context resolves nothing instead of throwing")
	fun closedContextResolvesNothing() {
		val context = loadContext()
		context.scope.publish(StationCapacity(2))
		context.close()

		assertThat(context.scope.publishedOrNull<StationCapacity>()).isNull()
	}

	@Test
	@DisplayName("an instance published under a supertype is resolved by that supertype only")
	fun publishedUnderSupertype() {
		val context = loadContext()
		val instance = StationCapacity(2)

		context.scope.publish<Any>(instance)

		assertThat(context.scope.publishedOrNull<Any>()).isSameInstanceAs(instance)
		assertThat(context.scope.publishedOrNull<StationCapacity>()).isNull()
	}

	@Test
	@DisplayName("a second publish of the same type into one context is rejected")
	fun secondPublishIsRejected() {
		val slots = ContextSlots()
		slots.put(StationCapacity::class, StationCapacity(2))

		assertFailure { slots.put(StationCapacity::class, StationCapacity(4)) }
			.isInstanceOf(IllegalStateException::class)
	}
}
