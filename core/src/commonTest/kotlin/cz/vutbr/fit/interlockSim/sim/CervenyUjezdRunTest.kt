/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP3.1 (Issue #1163): deterministic Cerveny Ujezd run.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.testutil.ArrivalTally
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Three seeded trains on the Cerveny Ujezd fixture (`cerveny_ujezd.xml`: 4 InOuts, 12 switches,
 * 12 signals), run on a [ShuntingLoop] that derives its blocks and capacity from [Station], under
 * the synchronous rule-based dispatcher (no LLM agent). Runs on every KMP target.
 *
 * Vyhybna has no route with two switches; this scenario must have one, and the test reads it from
 * the reservation record ([PathReservationRegistry.getSwitches]) while the train is on the route.
 *
 * The headline fact is the exit order. `Train.actions()` reports each completed journey as a
 * TRAIN_EVENTS message, and [runSampled] hands it over on the sim thread, so
 * [ArrivalTally.arrivalOrder] is the report emission order. (The PR #1267 review rejected the
 * first recording, through the control-step callbacks: a tick's outgoing approvals were sorted,
 * so two trains swapping order inside one control tick would not have shown.)
 */
class CervenyUjezdRunTest : CommonKoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		/** Fixed generator seed, logged on every run. */
		private const val SEED: Long = 1163L
		private const val TRAINS: Int = 3
		private const val END_TIME: Long = 600L
		private const val MIN_ROUTE_SWITCHES: Int = 2

		/** Track blocks in the pinned `cerveny_ujezd.xml` fixture: every one gets the zero-state checks. */
		private const val GRAPH_BLOCKS: Int = 32
	}

	private class RunResult(
		val entered: Int,
		val exited: Int,
		val arrivalReports: Int,
		val exitOrder: List<Int>,
		val occupiedBlocks: Int,
		val ownedBlocks: Int,
		val graphBlockCount: Int,
		val stationBlocksInsideGraph: Boolean,
		val maxSwitchesOnARoute: Int
	)

	private fun runOnce(): RunResult {
		logger.info { "Cerveny Ujezd run: seed=$SEED trains=$TRAINS endTime=$END_TIME" }
		val ctx: DefaultSimulationContext =
			CommonTestFixtures.parseSimulationContext(NetworkResources.CERVENY_UJEZD_XML, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val registry = ctx.scope.get<PathReservationRegistry>()
		val loop = ShuntingLoop(ctx, END_TIME, seed = SEED, maxTrains = TRAINS)
		wireSynchronousDispatcher(ctx, loop)

		val dispatcherStep = checkNotNull(loop.controlStepListener)
		var maxSwitches = 0
		loop.controlStepListener =
			ControlStepListener {
				dispatcherStep.onControlStep()
				for (train in loop.getApprovedTrains()) {
					maxSwitches = maxOf(maxSwitches, registry.getSwitches(train.name).size)
				}
			}
		ctx.setMainProcess(loop)

		val arrivals = ArrivalTally()
		val startMs = currentTimeMillisKMP()
		runSampled(ctx) { message -> message?.let(arrivals::record) }
		val wallSeconds = (currentTimeMillisKMP() - startMs) / 1000.0

		// Within one run the train number IS the generation order: the name ("Train #4") carries
		// a process-wide, strictly increasing counter, so ranking the arrival emission order by
		// number gives the generation ordinal (0 = the first train this run generated), and two
		// runs compare orders even though the second run's names keep counting on.
		val arrivalNumbers = arrivals.arrivalOrder
		val sortedArrivalNumbers = arrivalNumbers.sorted()
		val exitOrder = arrivalNumbers.map { sortedArrivalNumbers.indexOf(it) }

		// Zero state over EVERY block of the network graph — not only the platform + entry/exit
		// blocks `Station.allBlocks` derives (8 of 32 here): the throat blocks are where route
		// leftovers live (PR #1267 review). Compared as block objects: the fixture names only
		// 9 of them (XML blocks without a `name` fall back to `""`/auto-names), names would lie.
		val graphBlocks = ctx.getGraph().values().toSet()
		val stationBlocks = Station.from(ctx).allBlocks.map { it.block }
		val result =
			RunResult(
				entered = loop.getTrainsEntered(),
				exited = loop.getTrainsExited(),
				arrivalReports = arrivals.count,
				exitOrder = exitOrder,
				occupiedBlocks = graphBlocks.count { registry.isOccupied(it) },
				ownedBlocks = graphBlocks.count { registry.getOwner(it) != null },
				graphBlockCount = graphBlocks.size,
				stationBlocksInsideGraph = stationBlocks.all { it in graphBlocks },
				maxSwitchesOnARoute = maxSwitches
			)
		logger.info {
			"Cerveny Ujezd run done: seed=$SEED entered=${result.entered} exited=${result.exited} " +
				"arrivalReports=${result.arrivalReports} exitOrder=${result.exitOrder} " +
				"occupied=${result.occupiedBlocks} maxSwitchesOnARoute=${result.maxSwitchesOnARoute} wall=${wallSeconds}s"
		}
		return result
	}

	/**
	 * One test, two runs: the native step budget is tight, so the completeness, multi-switch and
	 * determinism checks share the same two runs instead of paying for four.
	 */
	@Test
	fun threeSeededTrainsExitThroughAMultiSwitchRouteAndRepeatExactly() {
		val first = runOnce()
		val second = runOnce()

		assertThat(first.entered, name = "trains entered").isEqualTo(TRAINS)
		assertThat(first.exited, name = "trains exited").isEqualTo(TRAINS)
		assertThat(first.arrivalReports, name = "journey-completion reports").isEqualTo(TRAINS)
		assertThat(first.exitOrder, name = "exit order").hasSize(TRAINS)
		assertThat(first.graphBlockCount, name = "blocks in the graph").isEqualTo(GRAPH_BLOCKS)
		assertThat(first.stationBlocksInsideGraph, name = "station blocks are part of the graph").isTrue()
		assertThat(first.occupiedBlocks, name = "occupied blocks").isEqualTo(0)
		assertThat(first.ownedBlocks, name = "reserved blocks").isEqualTo(0)
		assertThat(first.maxSwitchesOnARoute, name = "switches on the longest route")
			.isGreaterThanOrEqualTo(MIN_ROUTE_SWITCHES)

		assertThat(second.entered, name = "trains entered, second run").isEqualTo(TRAINS)
		assertThat(second.exited, name = "trains exited, second run").isEqualTo(TRAINS)
		assertThat(second.arrivalReports, name = "journey-completion reports, second run").isEqualTo(TRAINS)
		assertThat(second.exitOrder, name = "exit order of the second run").isEqualTo(first.exitOrder)
		assertThat(second.occupiedBlocks, name = "occupied blocks, second run").isEqualTo(0)
		assertThat(second.ownedBlocks, name = "reserved blocks, second run").isEqualTo(0)
		assertThat(second.maxSwitchesOnARoute, name = "longest route of the second run")
			.isEqualTo(first.maxSwitchesOnARoute)
	}
}
