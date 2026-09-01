/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Deterministic, all-platform contract tests for the Issue #994 copy-on-write conversion.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.multiTrainSpecs
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Deterministic contract tests for the Issue #994 copy-on-write approved-train lists.
 *
 * The JVM-only race tests (`MultiTrainLoopSnapshotRaceTest`, `ShuntingLoopApprovedTrainsRaceTest`)
 * catch the original defect probabilistically, with real threads. This class pins the same
 * contracts deterministically, on the test thread, so they also run on `linuxX64Test`:
 *
 * 1. **Freeze** — a list returned by `getApprovedTrains()` never changes afterwards, even
 *    though the simulation keeps admitting and retiring trains. Before Issue #994 the getter
 *    copied the live mutable list, and the simulation mutated that list in place; a captured
 *    reference could therefore shrink or lose trains under it.
 * 2. **One instance per publication** — two reads with no publication in between return the
 *    same instance. Before Issue #994 the getter built a fresh copy per call, so it could
 *    also tear mid-copy on an off-thread read.
 *
 * The listener callbacks fire on the simulation thread (same thread that publishes), which is
 * what makes the captures deterministic: no scheduling race can hide or duplicate a capture.
 *
 * @since Issue #994
 */
class ApprovedTrainsSnapshotContractTest : CommonKoinTestBase() {
	private companion object {
		/** Trains for the [MultiTrainLoop] scenarios — enough for several admit/retire publications. */
		const val MULTI_TRAINS: Int = 6

		/** Admissions per wave — a small cap forces several admit/retire publications over the run. */
		const val MAX_CONCURRENT: Int = 2

		/**
		 * Simulation seconds between two consecutive train injections. Larger than the race
		 * test's 1.0: these tests pin contracts, not churn, and the spacing keeps the runs fast
		 * on both KMP targets.
		 */
		const val TRAIN_INTERVAL: Double = 5.0

		/** Short trains complete quickly, which maximises the admit/retire churn rate. */
		const val TRAIN_LENGTH: Double = 20.0

		/** Generous end time — every train must complete its journey before it. */
		const val MULTI_END_TIME: Long = 1200L

		/** `vyhybna.xml` journeys take roughly 50 simulation seconds each. */
		const val SHUNTING_END_TIME: Long = 600L
	}

	/** What [runCapturingApprovalSnapshots] collected during the run. */
	private class SnapshotCaptures(
		val snapshots: List<Pair<List<Train>, List<String>>>,
		val consecutiveReadsShareOneInstance: Boolean
	)

	/**
	 * Runs [ctx] to completion while a property-change listener captures `getApprovedTrains()`
	 * on every `TRAIN_APPROVED` report. Each capture records the returned reference and its
	 * content at capture time, plus whether two consecutive reads shared one instance.
	 */
	private fun runCapturingApprovalSnapshots(
		ctx: DefaultSimulationContext,
		loop: ApprovesTrains
	): SnapshotCaptures {
		val captured = mutableListOf<Pair<List<Train>, List<String>>>()
		var consecutiveReadsShareOneInstance = true
		val listener =
			ContextPropertyChangeListener { event ->
				if (event.propertyName != ReportType.TRAIN_APPROVED.name) {
					return@ContextPropertyChangeListener
				}
				val first = loop.getApprovedTrains()
				consecutiveReadsShareOneInstance =
					consecutiveReadsShareOneInstance &&
					first === loop.getApprovedTrains()
				captured.add(first to first.map { it.name })
			}
		ctx.addPropertyChangeListener(listener)
		try {
			ctx.run()
		} finally {
			ctx.removePropertyChangeListener(listener)
		}
		return SnapshotCaptures(captured.toList(), consecutiveReadsShareOneInstance)
	}

	/** Asserts the two contracts from the class KDoc against the collected [SnapshotCaptures]. */
	private fun assertSnapshotsFrozen(captures: SnapshotCaptures) {
		assertThat(captures.snapshots, name = "the listener captured approval snapshots").isNotEmpty()
		captures.snapshots.forEachIndexed { index, (snapshot, namesAtCapture) ->
			assertThat(
				snapshot.map { it.name },
				name = "snapshot #$index stayed frozen (was: $namesAtCapture)"
			).isEqualTo(namesAtCapture)
		}
		assertThat(
			captures.consecutiveReadsShareOneInstance,
			name = "two reads with no publication in between share one instance"
		).isTrue()
	}

	/**
	 * A train approved *during* an in-flight `startApprovedTrains` iteration was the first
	 * candidate for a third contract, but that scenario is unreachable: `approveTrains()` runs
	 * only from `iteration()`, on the same kDisco simulation thread that is suspended inside
	 * `startApprovedTrains`, so no admission can interleave with the iteration. The snapshot
	 * iteration in `startApprovedTrains` is defensive, as its KDoc states. The reachable
	 * neighbour is covered instead: [no approved train is lost when admission interleaves with
	 * blocked entries].
	 */
	@Test
	fun `getApprovedTrains snapshots stay frozen across later admissions and retirements`() {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		val loop =
			MultiTrainLoop(
				context = ctx,
				endTime = MULTI_END_TIME,
				trainSpecs = multiTrainSpecs(MULTI_TRAINS, TRAIN_INTERVAL, TRAIN_LENGTH),
				maxConcurrentTrains = MAX_CONCURRENT
			)
		ctx.setMainProcess(loop)

		val captures = runCapturingApprovalSnapshots(ctx, loop)

		assertThat(loop.getTrainsEntered(), name = "every spec was injected").isEqualTo(MULTI_TRAINS)
		assertThat(loop.getTrainsExited(), name = "every train retired during the run").isEqualTo(MULTI_TRAINS)
		assertSnapshotsFrozen(captures)
	}

	/** The same freeze and identity contracts for [ShuntingLoop]s approved-train set. */
	@Test
	fun `ShuntingLoop approved-train snapshots stay frozen across admissions and retirements`() {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.VYHYBNA_XML,
				get()
			)
			.tracked()
		val loop = prepareShuntingLoop(ctx, endTime = SHUNTING_END_TIME)

		val captures = runCapturingApprovalSnapshots(ctx, loop)

		assertThat(loop.getTrainsExited() > 0, name = "trains were retired during the run").isTrue()
		assertSnapshotsFrozen(captures)
	}

	/**
	 * The reachable neighbour of the unreachable mid-iteration admission (see the class KDoc):
	 * trains approved on later iterations, while earlier entries are still blocked in
	 * `reserveEntryPath` on the closed semaphore, must never be lost by `startApprovedTrains`
	 * iterating a snapshot instead of the live list — every spec must eventually enter and exit.
	 *
	 * The non-allowing semaphore starts every entry blocked; the path reservation then opens the
	 * route per train, so entries succeed in waves and admissions overlap with blocked entries.
	 */
	@Test
	fun `no approved train is lost when admission interleaves with blocked entries`() {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false).tracked()
		val loop =
			MultiTrainLoop(
				context = ctx,
				endTime = MULTI_END_TIME,
				trainSpecs = multiTrainSpecs(MULTI_TRAINS, TRAIN_INTERVAL, TRAIN_LENGTH),
				maxConcurrentTrains = MAX_CONCURRENT
			)
		ctx.setMainProcess(loop)

		ctx.run()

		assertThat(
			loop.getTrainsEntered(),
			name = "no train was lost across startApprovedTrains passes"
		).isEqualTo(MULTI_TRAINS)
		assertThat(loop.getTrainsExited(), name = "every train completed its journey").isEqualTo(MULTI_TRAINS)
	}
}
