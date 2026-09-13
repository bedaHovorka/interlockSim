/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #989 — consecutive clearance stops along a multi-signal route.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.AspectFlipOnce
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.assertStoodAtClearanceStopLine
import cz.vutbr.fit.interlockSim.testutil.runClearanceStopScenario
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Issue #989 — a train facing a *sequence* of restrictive signals stands a clearance short of
 * **each** one, never only the first.
 *
 * The single-signal ladder lives in [Issue989StopShortOfRestrictiveSignalTest]; this class adds
 * the multi-signal rung: after the first signal clears and is crossed, the braking target must
 * move to the next signal's stop line, not to its separator. The route is
 * `A —100 m— Sem1 —100 m— Sem2 —100 m— B`, reserved end to end, with both signals forced to
 * STOP (the reservation would otherwise light them) and each cleared at its own stand. The
 * scenario chain itself — reservation, forcing, sampling — is the shared
 * [runClearanceStopScenario].
 */
@Tag("integration-test")
@DisplayName("Issue #989 — consecutive clearance stops along a signal sequence")
class Issue989ConsecutiveClearanceStopsTest : KoinTestBase() {
	private companion object {
		/** Length of every block in the sequence fixture. */
		const val BLOCK_LENGTH = 100.0

		/** Simulation end time; the train has long exited by then. */
		const val RUNNING_END_TIME = 120L

		/** Proceed aspect each signal is cleared to at its stand. */
		val PROCEED_ASPECT = Signal.S30
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("the train stands a clearance short of each signal in the sequence, then exits")
	fun trainStopsShortOfEachSignalInTheSequence() {
		val topology = TestTopologies.linearPathWithSemaphoreSequenceNetwork(semaphoreCount = 2)
		topology.context.tracked()
		var standAtFirstSignal = -1.0
		var standAtSecondSignal = -1.0
		val clearFirst =
			AspectFlipOnce(
				topology.semaphores[0],
				PROCEED_ASPECT,
				trigger = {
					topology.semaphores[0].signal == Signal.STOP &&
						it.velocity == 0.0 &&
						it.totalDistance > 0.0
				},
				onFlip = { standAtFirstSignal = it.totalDistance }
			)
		// The second stand is past the first signal, so `> BLOCK_LENGTH` keeps this trigger
		// from firing while the train is still held at the first stop line.
		val clearSecond =
			AspectFlipOnce(
				topology.semaphores[1],
				PROCEED_ASPECT,
				trigger = {
					topology.semaphores[1].signal == Signal.STOP &&
						it.velocity == 0.0 &&
						it.totalDistance > BLOCK_LENGTH
				},
				onFlip = { standAtSecondSignal = it.totalDistance }
			)
		val run =
			runClearanceStopScenario(
				topology.context,
				semaphores = topology.semaphores,
				endTime = RUNNING_END_TIME
			) { _, sample ->
				clearFirst.onSample(sample)
				clearSecond.onSample(sample)
			}

		assertThat(clearFirst.fired, name = "first signal was cleared at its stand").isTrue()
		assertThat(clearSecond.fired, name = "second signal was cleared at its stand").isTrue()
		assertStoodAtClearanceStopLine(
			standAtFirstSignal,
			BLOCK_LENGTH,
			name = "distance travelled at the first stand"
		)
		assertStoodAtClearanceStopLine(
			standAtSecondSignal,
			2 * BLOCK_LENGTH,
			name = "distance travelled at the second stand"
		)
		assertThat(run.process.getTrainsExited(), name = "trains exited").isEqualTo(1)
		// The route is 300 m end to end; reaching the exit means the front crossed both signals.
		assertThat(run.train.totalDistance, name = "distance travelled")
			.isGreaterThan(3 * BLOCK_LENGTH - 5.0)
	}
}
