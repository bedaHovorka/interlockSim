/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #989 — the clearance stop on vyhybna's multi-section approach.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.AspectFlipOnce
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.assertStoodAtClearanceStopLine
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.runClearanceStopScenario
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Issue #989 acceptance on the bundled `vyhybna.xml`: the clearance stop on a **multi-section**
 * approach through a switch — the geometry the hand-built linear fixture cannot produce.
 *
 * The reserved route A → B runs the shorter upper branch,
 * `A —100 m— zA —5 m— vA —5 m— doA1 —100 m— doB1 —5 m— vB —5 m— zB —100 m— B`.
 * Only separators facing the train guard it — [Train.separatorAction] gates the signal
 * branch on `isSeparatorInDirection`. Eastbound those are `zA` and `doB1`; `doA1` and `zB`
 * face the opposite way and never end a leg, so a train passes them under whatever aspect
 * they show. The reservation lights the facing route signals, and only `doB1` is forced
 * back to STOP — the one stop line this journey can meet.
 *
 * The stop line then lies at 210 − 1 = 209 m from A, and reaching it is the multi-section
 * case: past `zA` the reserved leg runs three blocks and 110 m to `doB1`, across the
 * switch `vA` and past the rear-facing `doA1`, both crossed while the clearance term is
 * still around a hundred metres out. That is the `boundaryGuard` `minOf` behaviour the
 * linear ladder cannot produce — through the intermediate block ends the *section
 * boundary* must win the minimum so the gate releases there and the crossing machinery
 * runs; only in the final block does the clearance term bind and bring the front to a
 * stand a metre short of the signal.
 *
 * `doB1` is cleared at the stand (the [AspectFlipOnce] pattern) and the train must finish
 * the journey from there: the stand lands inside the stop-line window, one train exits at
 * B, and the full 320 m route is covered.
 */
@Tag("integration-test")
@DisplayName("Issue #989 — the clearance stop on vyhybna's multi-section approach")
class Issue989VyhybnaMultiSectionStopTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private companion object {
		/** Distance from A to `doB1` along the reserved route: 100 m + 5 m + 5 m + 100 m. */
		const val DISTANCE_TO_DOB1 = 210.0

		/** Full length of the reserved route A → B: three 100 m blocks and four 5 m blocks. */
		const val ROUTE_LENGTH = 320.0

		/** Train length; the whole train stands inside the doA1 → doB1 block, clear of both switches. */
		const val TRAIN_LENGTH = 20.0

		/** Simulation end time; ample for the approach, the hold, and the S30 run to B. */
		const val END_TIME = 90L

		/** Proceed aspect `doB1` is cleared to at its stand. */
		val PROCEED_ASPECT = Signal.S30
	}

	private fun loadVyhybnaContext() =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("the train stands a clearance short of doB1 after the switch and the rear-facing signal")
	fun trainStopsAClearanceShortOfDoB1AcrossTheMultiSectionApproach() {
		val context = loadVyhybnaContext().tracked()
		val doB1 = context.cellsOfType<DynamicRailSemaphore>().single { it.name == "doB1" }

		var standAt = -1.0
		val clearDoB1 =
			AspectFlipOnce(
				doB1,
				PROCEED_ASPECT,
				trigger = {
					doB1.signal == Signal.STOP &&
						it.velocity == 0.0 &&
						it.totalDistance > 0.0
				},
				onFlip = { standAt = it.totalDistance }
			)

		val run =
			runClearanceStopScenario(
				context,
				semaphores = listOf(doB1),
				endTime = END_TIME,
				trainLength = TRAIN_LENGTH
			) { _, sample ->
				clearDoB1.onSample(sample)
			}

		assertThat(clearDoB1.fired, name = "the train stood at doB1's stop line").isTrue()
		assertStoodAtClearanceStopLine(standAt, DISTANCE_TO_DOB1, name = "distance travelled at the stand")
		assertThat(run.train.totalDistance, name = "distance travelled").isGreaterThan(ROUTE_LENGTH - 5.0)
		assertThat(run.process.getTrainsExited(), name = "trains exited").isEqualTo(1)
	}
}
