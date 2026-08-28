/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP5: Two-train concurrency validation (Issue #587).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

@Tag("integration-test")
@DisplayName("TwoTrainConcurrencyTest — Goal 1 SP5 (#587)")
class TwoTrainConcurrencyTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	/** Creates a fresh linear-with-semaphore context for each test repetition. */
	private fun newLinearContext(): DefaultSimulationContext {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
		ctx.getInOuts()
		context = ctx
		return ctx
	}

	// ------------------------------------------------------------------
	// Test 1: both trains complete without deadlock (50 runs)
	// ------------------------------------------------------------------

	/**
	 * Acceptance criteria:
	 * - Both trains enter and exit.
	 * - No occupied resources (kDisco Resource tokens) remain after run.
	 * - Run completes within timeout → no deadlock.
	 */
	@RepeatedTest(50)
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("Both trains complete without deadlock")
	fun bothTrainsCompleteWithoutDeadlock() {
		val ctx = newLinearContext()
		val process =
			MultiTrainLoop(
				ctx,
				endTime = 600L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0),
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 1.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		assertThat(process.getTrainsEntered()).isEqualTo(2)
		assertThat(process.getTrainsExited()).isEqualTo(2)
		assertThat(process.getOccupiedResourceCount()).isZero()
	}

	// ------------------------------------------------------------------
	// Test 2: block-transition ordering is consistent (50 runs)
	// ------------------------------------------------------------------

	/**
	 * Acceptance criteria:
	 * - Every TRAIN_APPROVED message (route announcement) appears in the log
	 *   before the corresponding "ends" message for the same train.
	 * - Two trains approved → two trains ended, in a consistent order.
	 */
	@RepeatedTest(50)
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("TRAIN_APPROVED precedes ends for each train (50 runs)")
	fun trainApprovedPrecedesEndsForEachTrain() {
		val ctx = newLinearContext()

		// Collect all TRAIN_APPROVED and TRAIN_EVENTS messages in arrival order.
		// CopyOnWriteArrayList not needed (single sim thread), but clarifies intent.
		data class LogEntry(
			val type: ReportType,
			val message: String
		)
		val log = mutableListOf<LogEntry>()

		ctx.addPropertyChangeListener(
			ContextPropertyChangeListener { event ->
				when (event.propertyName) {
					ReportType.TRAIN_APPROVED.name ->
						log.add(LogEntry(ReportType.TRAIN_APPROVED, event.newValue?.toString() ?: ""))
					ReportType.TRAIN_EVENTS.name ->
						log.add(LogEntry(ReportType.TRAIN_EVENTS, event.newValue?.toString() ?: ""))
				}
			}
		)

		val process =
			MultiTrainLoop(
				ctx,
				endTime = 600L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0),
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 1.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run()

		val approvedEntries = log.filter { it.type == ReportType.TRAIN_APPROVED }
		val endsEntries = log.filter { it.type == ReportType.TRAIN_EVENTS && it.message.contains("ends") }

		// Both trains must have been approved and must have ended
		assertThat(approvedEntries.size).isEqualTo(2)
		assertThat(endsEntries.size).isEqualTo(2)

		// Per-train: each train's TRAIN_APPROVED must precede its own "ends" event.
		// Trains may run sequentially, so global ordering across both trains is not required.
		//
		// Train names come from a process-global counter that is never reset (Train.countValue),
		// so by the time this test runs the numbers may be multi-digit. Names must therefore be
		// compared as extracted whole tokens: a substring test would match "Train #1" inside
		// "Train #12". Likewise, positions must come from withIndex() rather than List.indexOf(),
		// which returns the first *equal* LogEntry rather than this one.
		val trainNameRegex = Regex("""Train #\d+""")

		fun LogEntry.trainName(): String? = trainNameRegex.find(message)?.value

		val indexOfEntry: (LogEntry) -> Int = { target ->
			log.withIndex().first { (_, entry) -> entry === target }.index
		}

		for (approvalEntry in approvedEntries) {
			val trainName = approvalEntry.trainName() ?: continue
			val approvalIdx = indexOfEntry(approvalEntry)
			val endsEntry = endsEntries.firstOrNull { it.trainName() == trainName }
			requireNotNull(endsEntry) { "No 'ends' event found for $trainName" }
			val endsIdx = indexOfEntry(endsEntry)
			assertThat(endsIdx).isGreaterThan(approvalIdx)
		}
	}

	// ------------------------------------------------------------------
	// Test 3: same-step arrival does not cause exception (50 runs)
	// ------------------------------------------------------------------

	/**
	 * Acceptance criteria:
	 * - Both trains injected at inTime = 0.0 (simultaneous arrival edge case).
	 * - Both complete; no exception thrown; no resources leaked.
	 * This exercises the scheduler tie-breaking path where two activations
	 * land in the same kDisco event slot.
	 */
	@RepeatedTest(50)
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("Same-step arrival (inTime=0.0 for both) completes without exception")
	fun sameStepArrivalNoException() {
		val ctx = newLinearContext()
		val process =
			MultiTrainLoop(
				ctx,
				endTime = 600L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0),
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)
		ctx.setMainProcess(process)
		ctx.run() // must not throw

		assertThat(process.getTrainsEntered()).isEqualTo(2)
		assertThat(process.getTrainsExited()).isEqualTo(2)
		assertThat(process.getOccupiedResourceCount()).isZero()
	}
}
