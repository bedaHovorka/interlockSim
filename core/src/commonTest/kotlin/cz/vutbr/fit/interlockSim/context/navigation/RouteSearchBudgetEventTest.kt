/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import kotlinx.coroutines.runBlocking
import org.koin.core.component.get
import kotlin.test.Test

/**
 * A route search stopped by its node budget is announced as one
 * [RunAccountingEvent.RouteSearchBudgetExceeded] (Issue #1306, Goal 6 SP8), where the one WARN line
 * of Issue #1151 is logged, so the run record can count it.
 *
 * @since Issue #1306 (Goal 6 SP8)
 */
class RouteSearchBudgetEventTest : CommonKoinTestBase() {
	private companion object {
		/** A budget far below the 13 nodes the vyhybna InOut-to-InOut search expands. */
		const val TINY_BUDGET: Int = 5
		const val MAX_DEPTH: Int = 100
	}

	/** Runs [search] inside a kDisco process and returns the budget events it announced. */
	private fun budgetEventsOf(search: () -> Unit): List<RunAccountingEvent.RouteSearchBudgetExceeded> {
		val announced = mutableListOf<RunAccountingEvent.RouteSearchBudgetExceeded>()
		val searcher =
			object : Process() {
				override suspend fun actions() = search()
			}
		runBlocking {
			val simulation = Simulation.create { Process.activate(searcher) }
			simulation.onEvent { event ->
				val payload = (event as? SimulationEvent.Custom)?.payload
				if (payload is RunAccountingEvent.RouteSearchBudgetExceeded) announced += payload
			}
			simulation.run(1.0)
		}
		return announced
	}

	private fun context(): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(NetworkResources.VYHYBNA_XML, get()).tracked()

	@Test
	fun anOverBudgetSearchIsAnnouncedOnceAlthoughItIsLookedUpTwice() {
		val ctx = context()
		val (from, to) = ctx.getInOuts().toList()
		val navigator = DefaultTopologyNavigator(ctx, cacheRoutes = true, routeNodeBudget = TINY_BUDGET)

		val events =
			budgetEventsOf {
				navigator.findAllSwitchConstrainedPaths(from, to, MAX_DEPTH)
				navigator.findAllSwitchConstrainedPaths(from, to, MAX_DEPTH)
			}

		assertThat(events.size, name = "budget events").isEqualTo(1)
		val event = events.single()
		assertThat(event.nodeBudget, name = "budget").isEqualTo(TINY_BUDGET)
		assertThat(event.start, name = "start").isEqualTo(from.toString())
		assertThat(event.target, name = "target").isEqualTo(to.toString())
	}

	@Test
	fun aSearchWithinItsBudgetAnnouncesNothing() {
		val ctx = context()
		val (from, to) = ctx.getInOuts().toList()
		val navigator = DefaultTopologyNavigator(ctx)

		assertThat(budgetEventsOf { navigator.findAllSwitchConstrainedPaths(from, to, MAX_DEPTH) }).isEmpty()
	}
}
