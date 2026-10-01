/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Stress test for Issues #1030, #1028: off-thread reads of Train.frontIdentity are consistent.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.ConcurrentReadProbeResult
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.isWestToEastEntryEnd
import cz.vutbr.fit.interlockSim.testutil.multiTrainSpecs
import cz.vutbr.fit.interlockSim.testutil.probeConcurrentReads
import cz.vutbr.fit.interlockSim.testutil.sameStatic
import cz.vutbr.fit.interlockSim.testutil.separatorGridColumns
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Stress test for [Train.frontIdentity] (Issues #1030, #1028).
 *
 * A multi-train run on a 6-block linear line executes on its own thread while a reader thread —
 * standing in for the Swing EDT that `AnimationStateCapture` runs on — reads every approved
 * train's identity in a tight loop. Every identity read must be internally consistent: when it
 * names both a section and an entry separator, the separator is one of that section's two ends
 * and, because every train runs west to east on this straight line (growing grid columns), it is
 * the end in the smaller column. The second check is the one the real crossing-block tear fails:
 * that tear pairs a section with its own exit end, which the first check alone accepts.
 *
 * Reading the live getters one by one instead (`frontSection`, then `trainEntrySeparator`) can
 * pair the section just traversed with its own exit end, because the simulation thread changes
 * the underlying fields one statement at a time at every crossing. One volatile
 * immutable identity cannot be torn that way.
 *
 * Manual-only (`heavy-test`): the reader spins for the whole run.
 */
@Tag("heavy-test")
@DisplayName("Off-thread Train.frontIdentity reads are always consistent (Issues #1030, #1028)")
class TrainFrontIdentityConcurrentReadHeavyTest : KoinTestBase() {
	private companion object {
		const val TRAIN_COUNT: Int = 40
		const val TRAIN_INTERVAL: Double = 2.0
		const val TRAIN_LENGTH: Double = 30.0
		const val END_TIME: Long = 3000L
		const val MAX_CONCURRENT_TRAINS: Int = 10
		const val BLOCK_LENGTH: Double = 60.0
		const val SPEED_LIMIT: Double = 80.0
		val JOIN_TIMEOUT_MILLIS: Long = TimeUnit.MINUTES.toMillis(8)
	}

	private fun endsLabel(section: TrackSection): String = section.ends().joinToString("..") { separatorLabel(it) }

	/**
	 * Tallies of the reader thread's checks. The reader thread is their only writer, and the
	 * assertions run after [probeConcurrentReads] joined it (the join is the happens-before
	 * edge), so plain fields are enough — a shared atomic per check would cost more than the
	 * reads under test and would shrink the race window this stress test exists to maximize
	 * (see `ConcurrentReadProbe`).
	 */
	private class IdentityCheckCounters {
		var checked = 0L
		var torn = 0L
		var wrongDirection = 0L
		var firstTorn: String? = null
		var firstWrongDirection: String? = null
	}

	/**
	 * One reader-thread check of one train's identity: internally consistent when the entry
	 * separator it names is an end of the section it names and — every train here runs west to
	 * east, towards growing grid columns — the end in the smaller column (see the class KDoc
	 * for why both checks are needed).
	 */
	private fun checkTrainIdentity(
		train: Train,
		columns: Map<PathSeparator, Int>,
		counters: IdentityCheckCounters
	) {
		val identity = train.frontIdentity
		val section = identity.section ?: return
		val entry = identity.entrySeparator ?: return
		counters.checked++
		if (section.ends().none { sameStatic(it, entry) }) {
			counters.torn++
			if (counters.firstTorn == null) {
				counters.firstTorn =
					"train #${train.trainNumber}: entry ${separatorLabel(entry)} " +
					"is not an end of ${endsLabel(section)}"
			}
		} else if (!isWestToEastEntryEnd(columns, section, entry)) {
			// The crossing-block tear pairs a section with its own exit end.
			counters.wrongDirection++
			if (counters.firstWrongDirection == null) {
				counters.firstWrongDirection =
					"train #${train.trainNumber}: entry ${separatorLabel(entry)} " +
					"is the exit end of ${endsLabel(section)}"
			}
		}
	}

	/** Logs what the probe saw, then asserts the outcome the test method's name promises. */
	private fun assertProbeOutcome(
		result: ConcurrentReadProbeResult,
		loop: MultiTrainLoop,
		counters: IdentityCheckCounters
	) {
		logger.info {
			"Issue #1030 identity probe: reads=${result.totalReads} checked=${counters.checked} " +
				"torn=${counters.torn} wrongDirection=${counters.wrongDirection} " +
				"exited=${loop.getTrainsExited()} failures=${result.failures}"
		}

		assertThat(result.failures, name = "no exception escaped the simulation or an identity read").isEmpty()
		assertThat(counters.checked, name = "identities with a section and an entry were read")
			.isGreaterThan(0L)
		assertThat(loop.getTrainsExited(), name = "trains completed their journeys").isGreaterThan(0)
		assertThat(counters.torn, name = "torn identities (first: ${counters.firstTorn})").isEqualTo(0L)
		assertThat(
			counters.wrongDirection,
			name = "identities entered through the exit end (first: ${counters.firstWrongDirection})"
		).isEqualTo(0L)
	}

	private fun sixBlockLine(): DefaultSimulationContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withSemaphore(3, 3, false)
			.withSemaphore(5, 5, false)
			.withSemaphore(7, 7, false)
			.withSemaphore(9, 9, false)
			.withSemaphore(11, 11, false)
			.withInOut("B", 13, 13, false)
			.withConnection(1, 1, 3, 3, BLOCK_LENGTH, SPEED_LIMIT)
			.withConnection(3, 3, 5, 5, BLOCK_LENGTH, SPEED_LIMIT)
			.withConnection(5, 5, 7, 7, BLOCK_LENGTH, SPEED_LIMIT)
			.withConnection(7, 7, 9, 9, BLOCK_LENGTH, SPEED_LIMIT)
			.withConnection(9, 9, 11, 11, BLOCK_LENGTH, SPEED_LIMIT)
			.withConnection(11, 11, 13, 13, BLOCK_LENGTH, SPEED_LIMIT)
			.buildSimulationContext()
			.tracked()

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("every off-thread identity names an entry separator that is an end of its section")
	fun `off-thread identity reads are never torn`() {
		val ctx = sixBlockLine()
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs = multiTrainSpecs(TRAIN_COUNT, TRAIN_INTERVAL, TRAIN_LENGTH),
				maxConcurrentTrains = MAX_CONCURRENT_TRAINS
			)
		ctx.setMainProcess(loop)

		val columns = separatorGridColumns(ctx)
		val counters = IdentityCheckCounters()

		val result =
			probeConcurrentReads(
				readerCount = 1,
				joinTimeoutMillis = JOIN_TIMEOUT_MILLIS,
				threadNamePrefix = "issue-1030",
				readOnce = {
					for (train in loop.getApprovedTrains()) {
						checkTrainIdentity(train, columns, counters)
					}
				},
				runSimulation = { ctx.run() }
			)

		assertProbeOutcome(result, loop, counters)
	}
}
