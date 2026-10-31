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
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
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
 *
 * The exit side is pinned too (Issue #1278): per train, in generation order, its origin, its
 * timetable destination and the station exit its last route actually ended at, plus the number of
 * `ends in wrong out` reports. Červený Újezd has two exits per side, so the exit a train takes is
 * the dispatcher's choice, not a topological given.
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

		/** `Train`'s tail reports this TRAIN_EVENTS text when the train leaves by another exit than its timetable's. */
		private const val WRONG_OUT_SUFFIX: String = " ends in wrong out"

		/** The fixture's InOut names (`cerveny_ujezd.xml`): two exits per side. */
		private const val WEST_ENTRY_1: String = "west_entry_1"
		private const val WEST_ENTRY_2: String = "west_entry_2"
		private const val EAST_EXIT_1: String = "east_exit_1"
		private const val EAST_EXIT_2: String = "east_exit_2"
	}

	/**
	 * One journey's exit side: where the train came from, where its timetable sends it, and the
	 * station exit its last route ended at (the exit it physically left by).
	 */
	private data class Journey(
		val origin: String,
		val destination: String,
		val exitTaken: String?
	)

	/**
	 * The run's exit side: the exit order (generation ordinals in arrival-report order), every
	 * [Journey] in generation order, and the `ends in wrong out` report count.
	 */
	private data class ExitSide(
		val order: List<Int>,
		val journeys: List<Journey>,
		val wrongOutReports: Int
	)

	private class RunResult(
		val entered: Int,
		val exited: Int,
		val arrivalReports: Int,
		val occupiedBlocks: Int,
		val ownedBlocks: Int,
		val graphBlockCount: Int,
		val stationBlocksInsideGraph: Boolean,
		val maxSwitchesOnARoute: Int,
		val exitSide: ExitSide
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
		// Keyed by train number; a train's last route that ends at an InOut is the exit it leaves by.
		val origins = mutableMapOf<Int, String>()
		val destinations = mutableMapOf<Int, String>()
		val exitsTaken = mutableMapOf<Int, String>()
		loop.controlStepListener =
			ControlStepListener {
				dispatcherStep.onControlStep()
				for (train in loop.getApprovedTrains()) {
					maxSwitches = maxOf(maxSwitches, registry.getSwitches(train.name).size)
					origins[train.trainNumber] = train.originInOut.name
					destinations[train.trainNumber] = train.timetableDestinationName
					val target = registry.getPathInfo(train.name)?.target
					if (target is DynamicInOut) exitsTaken[train.trainNumber] = target.name
				}
			}
		ctx.setMainProcess(loop)

		val arrivals = ArrivalTally()
		var wrongOutReports = 0
		val startMs = currentTimeMillisKMP()
		runSampled(ctx) { message ->
			message?.let(arrivals::record)
			if (message?.endsWith(WRONG_OUT_SUFFIX) == true) wrongOutReports++
		}
		val wallSeconds = (currentTimeMillisKMP() - startMs) / 1000.0

		// Within one run the train number IS the generation order: the name ("Train #4") carries
		// a process-wide, strictly increasing counter, so ranking the arrival emission order by
		// number gives the generation ordinal (0 = the first train this run generated), and two
		// runs compare orders even though the second run's names keep counting on.
		val arrivalNumbers = arrivals.arrivalOrder
		val sortedArrivalNumbers = arrivalNumbers.sorted()
		val exitOrder = arrivalNumbers.map { sortedArrivalNumbers.indexOf(it) }
		val journeys =
			destinations.keys.sorted().map { number ->
				Journey(
					origin = checkNotNull(origins[number]),
					destination = checkNotNull(destinations[number]),
					exitTaken = exitsTaken[number]
				)
			}

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
				occupiedBlocks = graphBlocks.count { registry.isOccupied(it) },
				ownedBlocks = graphBlocks.count { registry.getOwner(it) != null },
				graphBlockCount = graphBlocks.size,
				stationBlocksInsideGraph = stationBlocks.all { it in graphBlocks },
				maxSwitchesOnARoute = maxSwitches,
				exitSide = ExitSide(exitOrder, journeys, wrongOutReports)
			)
		logger.info {
			"Cerveny Ujezd run done: seed=$SEED entered=${result.entered} exited=${result.exited} " +
				"arrivalReports=${result.arrivalReports} exitOrder=${result.exitSide.order} " +
				"occupied=${result.occupiedBlocks} maxSwitchesOnARoute=${result.maxSwitchesOnARoute} " +
				"exitSide=${result.exitSide} wall=${wallSeconds}s"
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
		assertThat(first.exitSide.order, name = "exit order").hasSize(TRAINS)
		assertThat(first.graphBlockCount, name = "blocks in the graph").isEqualTo(GRAPH_BLOCKS)
		assertThat(first.stationBlocksInsideGraph, name = "station blocks are part of the graph").isTrue()
		assertThat(first.occupiedBlocks, name = "occupied blocks").isEqualTo(0)
		assertThat(first.ownedBlocks, name = "reserved blocks").isEqualTo(0)
		assertThat(first.maxSwitchesOnARoute, name = "switches on the longest route")
			.isGreaterThanOrEqualTo(MIN_ROUTE_SWITCHES)
		// Exit side as the nearest-exit-first policy produces it (Issue #1278): trains 2 and 3
		// leave by the other east exit than their timetable names, and each reports it.
		assertThat(first.exitSide.journeys, name = "journeys in generation order").containsExactly(
			Journey(origin = WEST_ENTRY_1, destination = EAST_EXIT_1, exitTaken = EAST_EXIT_1),
			Journey(origin = WEST_ENTRY_2, destination = EAST_EXIT_1, exitTaken = EAST_EXIT_2),
			Journey(origin = WEST_ENTRY_1, destination = EAST_EXIT_2, exitTaken = EAST_EXIT_1)
		)
		assertThat(first.exitSide.wrongOutReports, name = "'ends in wrong out' reports").isEqualTo(2)

		assertThat(second.entered, name = "trains entered, second run").isEqualTo(TRAINS)
		assertThat(second.exited, name = "trains exited, second run").isEqualTo(TRAINS)
		assertThat(second.arrivalReports, name = "journey-completion reports, second run").isEqualTo(TRAINS)
		assertThat(second.exitSide.order, name = "exit order of the second run").isEqualTo(first.exitSide.order)
		assertThat(second.occupiedBlocks, name = "occupied blocks, second run").isEqualTo(0)
		assertThat(second.ownedBlocks, name = "reserved blocks, second run").isEqualTo(0)
		assertThat(second.maxSwitchesOnARoute, name = "longest route of the second run")
			.isEqualTo(first.maxSwitchesOnARoute)
		assertThat(second.exitSide, name = "exit side of the second run").isEqualTo(first.exitSide)
	}
}
