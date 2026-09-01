/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test for Issue #788: the published front state
 * (frontSection, trainEntrySeparator, frontPosition) must stay coherent on the
 * simulation thread that publishes it.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.ArrivalTally
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.multiTrainSpecs
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.testutil.sameStatic
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Regression test for Issue #788 — the stale `(frontSection, entrySeparator)` pair at
 * boundaries where the front has no section to advance into.
 *
 * ## The invariant
 *
 * A train enters a [TrackSection] through exactly one of its two ends and leaves through the
 * other; it never re-enters the same section through the opposite end without a direction
 * reversal (which no train in these scenarios performs). Therefore, **while `frontSection`
 * keeps reporting the same section, `trainEntrySeparator` must not change.**
 *
 * That single rule pins the rendered heading, because
 * `TrainPositionCalculator.calculateTrainHeadingRadians` derives it as `atan2(exit − entry)`
 * with `exit = frontSection.getSecondEnd(trainEntrySeparator)`. Swapping the entry end for the
 * exit end of the *same* section reverses that vector by exactly 180°.
 *
 * ## Why the existing race test cannot catch this
 *
 * [TrainEntrySeparatorRaceTest] asserts only that `trainEntrySeparator` is *one of the two
 * ends* of `frontSection`. The stale pair satisfies that — the separator just crossed is an
 * end of the section just traversed. It is simply the **wrong** end.
 *
 * ## The boundary state
 *
 * There is only one such state in `Train.Site.actions()`: the front has crossed out of its
 * section and has no section to enter. It is reached at the destination InOut (permanently),
 * while the reserved route has not been extended past the separator yet, and while the front
 * waits in front of a STOP signal.
 *
 * ## Scenarios
 *
 * - [published front state stays coherent after arrival]: [MultiTrainLoop] reserves the whole
 *   entry-to-exit path before admitting a train, so the boundary state it reaches is the
 *   destination InOut. This scenario also pins the boundary pair's **values** post-run
 *   ([assertArrivalBoundaryPairValues]): the coherence checks above only compare consecutive
 *   samples, so a wrong-but-stable pair would slip through them.
 * - [published front state stays coherent across an incremental-reservation run]: [ShuntingLoop]
 *   extends each route block by block under the rule-based dispatcher, which is the production
 *   path through the same code and exercises every separator of `vyhybna.xml`.
 *
 * "Coherent on the simulation thread" is deliberately not "atomic": the sampler runs on the
 * kDisco thread, the same thread that mutates the fields, so it never sees a torn state. A
 * cross-thread consumer reading the three properties one by one can — that is a separate,
 * pre-existing concern tracked outside this test.
 *
 * @since Issue #788
 */
@DisplayName("Published front state stays coherent at section boundaries (Issue #788)")
class TrainFrontBoundaryStateTest : KoinTestBase() {
	private companion object {
		const val TRAIN_LENGTH: Double = 40.0
		const val MULTI_TRAIN_END_TIME: Long = 600L
		const val SHUNTING_END_TIME: Long = 700L

		/** Lower bound on completed journeys in the shunting run — a non-vacuity guard. */
		const val MIN_SHUNTING_ARRIVALS: Int = 5

		/** Destination InOut of the 4-block linear scenario. */
		const val DESTINATION_NAME: String = "B"

		/** How close the published position must sit to the section length at the boundary. */
		const val BOUNDARY_POSITION_TOLERANCE: Double = 1.0e-6
	}

	/** Last published `(frontSection, trainEntrySeparator, frontPosition)` triple seen for a train. */
	private data class PublishedFront(
		val section: TrackSection,
		val entry: PathSeparator,
		val position: Double
	)

	private val lastPublished = mutableMapOf<Int, PublishedFront>()

	/**
	 * Last triple seen in the boundary state — the front standing at the far end of the very
	 * section it reports, which is the only state where the published position equals the
	 * section length.
	 */
	private val boundaryPublished = mutableMapOf<Int, PublishedFront>()
	private val violations = mutableListOf<String>()
	private val arrivals = ArrivalTally()

	/** Cross-section checks skipped because the two sections share no unambiguous end. */
	private var skippedChecks = 0

	/** Samples of the origin-wait state: no section yet, but an entry separator already set. */
	private var originWaitSamples = 0
	private val originWaitViolations = mutableListOf<String>()

	/**
	 * The single separator shared by two sections, or `null` when they do not touch (the
	 * sampler skipped a section) or share both ends (ambiguous parallel tracks).
	 */
	private fun sharedEnd(
		a: TrackSection,
		b: TrackSection
	): PathSeparator? = a.ends().filter { end -> b.ends().any { sameStatic(it, end) } }.singleOrNull()

	private fun sample(trains: List<Train>) {
		for (train in trains) {
			val section = train.frontSection
			if (section == null) {
				// Origin-wait state: the front has not entered any section, so the published
				// entry is raw — `traversedSectionAtExit()` has nothing to correct. The only
				// separator it can legitimately name is the origin InOut.
				val entry = train.trainEntrySeparator ?: continue
				originWaitSamples++
				if (separatorLabel(entry) != train.timetableOriginName) {
					originWaitViolations.add(
						"train #${train.trainNumber}: no front section yet, but the published entry is " +
							"${separatorLabel(entry)}, not the origin InOut ${train.timetableOriginName}"
					)
				}
				continue
			}
			val entry = train.trainEntrySeparator ?: continue
			val published = PublishedFront(section, entry, train.frontPosition)
			lastPublished[train.trainNumber]?.let { previous -> check(train, previous, section, entry) }
			lastPublished[train.trainNumber] = published
			if (abs(published.position - section.length()) <= BOUNDARY_POSITION_TOLERANCE) {
				boundaryPublished[train.trainNumber] = published
			}
		}
	}

	/**
	 * Checks one published pair against the previous one for the same train.
	 *
	 * Two rules, both consequences of "a train enters a section through one end and leaves
	 * through the other":
	 * 1. while `frontSection` reports the same section, `trainEntrySeparator` cannot move;
	 * 2. when `frontSection` advances to an adjacent section, the separator they share is the
	 *    end the front left the old section through — so it must be the *new* entry, and it
	 *    must not have been the *old* one.
	 */
	private fun check(
		train: Train,
		previous: PublishedFront,
		section: TrackSection,
		entry: PathSeparator
	) {
		val detail =
			"frontPosition=${train.frontPosition}, sectionLength=${section.length()}"
		if (previous.section === section) {
			if (!sameStatic(previous.entry, entry)) {
				violations.add(
					"train #${train.trainNumber} on ${blockLabel(section)}: trainEntrySeparator moved " +
						"${separatorLabel(previous.entry)} -> ${separatorLabel(entry)} without the front leaving the " +
						"section (heading reversed 180 degrees); $detail"
				)
			}
			return
		}
		val shared = sharedEnd(previous.section, section)
		if (shared == null) {
			skippedChecks++
			return
		}
		if (sameStatic(previous.entry, shared)) {
			violations.add(
				"train #${train.trainNumber}: the front left ${blockLabel(previous.section)} through " +
					"${separatorLabel(shared)}, so it cannot have entered that section through the same end, " +
					"yet trainEntrySeparator said it did (heading reversed 180 degrees); $detail"
			)
		} else if (!sameStatic(entry, shared)) {
			violations.add(
				"train #${train.trainNumber} on ${blockLabel(section)}: the front entered through " +
					"${separatorLabel(shared)} but trainEntrySeparator is ${separatorLabel(entry)}; $detail"
			)
		}
	}

	private fun blockLabel(section: TrackSection): String = section.ends().joinToString("..") { separatorLabel(it) }

	/** Runs [ctx] with the boundary-state sampler attached to every train event, then prints the witnesses. */
	private fun runSampledWithWitness(
		ctx: DefaultSimulationContext,
		trains: () -> List<Train>
	) {
		runSampled(ctx, setOf(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS)) { message ->
			message?.let(arrivals::record)
			sample(trains())
		}
		violations.take(10).forEach { println("FRONT_STATE_VIOLATION: $it") }
		println(
			"WITNESS: arrivals=${arrivals.count} distinctArrived=${arrivals.arrivedTrainNumbers.size} " +
				"violations=${violations.size} skippedChecks=$skippedChecks " +
				"boundarySamples=${boundaryPublished.size} originWaitSamples=$originWaitSamples"
		)
	}

	/**
	 * The origin-wait state (front not yet in any section) is the one boundary where the
	 * published entry is raw: `traversedSectionAtExit()` is null although `onNext` is false,
	 * so `Train.Site.publishedEntrySeparator()` returns the crossed separator unchanged.
	 * The only separator it can legitimately name is the origin InOut that the front crossed
	 * when it started.
	 */
	private fun assertOriginWaitEntries() {
		assertThat(
			originWaitSamples > 0,
			name = "the origin-wait state was sampled"
		).isTrue()
		assertThat(
			originWaitViolations.isEmpty(),
			name = "origin-wait entries name the origin InOut (violations: ${originWaitViolations.take(3)})"
		).isTrue()
	}

	private fun assertNoInPlaceReversal() {
		assertThat(
			violations.isEmpty(),
			name = "no in-place entry/exit swap (violations: ${violations.take(3)})"
		).isTrue()
	}

	/**
	 * Post-run check: every train's boundary sample must describe the true arrival state —
	 * still on the section that ends at the destination InOut, entered through its **own**
	 * entry end (not the destination end), standing at the far end.
	 *
	 * The per-frame checks only compare consecutive samples, so a wrong-but-stable triple
	 * (for example a position published as `state + sectionLength`) would pass them. This
	 * pins the values themselves.
	 */
	private fun assertArrivalBoundaryPairValues() {
		val boundaryViolations = mutableListOf<String>()
		for ((trainNumber, front) in boundaryPublished) {
			val destinationEnd = front.section.ends().firstOrNull { separatorLabel(it) == DESTINATION_NAME }
			if (destinationEnd == null) {
				boundaryViolations.add(
					"train #$trainNumber on ${blockLabel(front.section)}: the reported section does not end " +
						"at the destination InOut $DESTINATION_NAME"
				)
				continue
			}
			val entryEnd = front.section.ends().singleOrNull { !sameStatic(it, destinationEnd) }
			if (entryEnd == null || !sameStatic(front.entry, entryEnd)) {
				boundaryViolations.add(
					"train #$trainNumber on ${blockLabel(front.section)}: published entry " +
						"${separatorLabel(front.entry)} is not the section's entry end " +
						"${separatorLabel(entryEnd)} (heading reversed 180 degrees)"
				)
			}
			if (abs(front.position - front.section.length()) > BOUNDARY_POSITION_TOLERANCE) {
				boundaryViolations.add(
					"train #$trainNumber on ${blockLabel(front.section)}: published position ${front.position} " +
						"is not the section length ${front.section.length()} — the front is not at the far end"
				)
			}
		}
		assertThat(
			boundaryPublished.isNotEmpty(),
			name = "the boundary state was sampled (arrivals=${arrivals.arrivedTrainNumbers.size})"
		).isTrue()
		assertThat(
			boundaryViolations.isEmpty(),
			name =
				"boundary triple values are the traversed section entered through its own entry end " +
					"(violations: ${boundaryViolations.take(3)})"
		).isTrue()
	}

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("arrival at the destination InOut does not swap the entry end in place")
	fun `published front state stays coherent after arrival`() {
		// 4-block linear topology: A -> Sem1 -> Sem2 -> Sem3 -> B (4 x 100 m).
		val ctx =
			TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withSemaphore(3, 3, false)
				.withSemaphore(5, 5, false)
				.withSemaphore(7, 7, false)
				.withInOut("B", 9, 9, false)
				.withConnection(1, 1, 3, 3, 100.0, 80.0)
				.withConnection(3, 3, 5, 5, 100.0, 80.0)
				.withConnection(5, 5, 7, 7, 100.0, 80.0)
				.withConnection(7, 7, 9, 9, 100.0, 80.0)
				.buildSimulationContext()
				.tracked()

		val loop =
			MultiTrainLoop(
				context = ctx,
				endTime = MULTI_TRAIN_END_TIME,
				trainSpecs = multiTrainSpecs(count = 2, interval = 2.0, length = TRAIN_LENGTH)
			)
		ctx.setMainProcess(loop)
		runSampledWithWitness(ctx) { loop.getApprovedTrains() }

		// Witness: both trains actually reached their destination InOut.
		assertThat(arrivals.count >= 2, name = "both trains reached the destination InOut").isTrue()
		assertNoInPlaceReversal()
		assertArrivalBoundaryPairValues()
		assertOriginWaitEntries()
	}

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("incremental route reservation never swaps the entry end in place")
	fun `published front state stays coherent across an incremental-reservation run`() {
		val ctx = TestFixtures.newShuntingSimulationContext(initializeDynamicMapping = true).tracked()
		val loop = prepareShuntingLoop(ctx, SHUNTING_END_TIME)
		runSampledWithWitness(ctx) { loop.getApprovedTrains() }

		// Witness: the run really carried trains end to end over the passing loop, and the
		// boundary state (standing at the far end of the reported section) was sampled.
		assertThat(arrivals.count >= MIN_SHUNTING_ARRIVALS, name = "the shunting run completed journeys").isTrue()
		assertThat(
			boundaryPublished.isNotEmpty(),
			name = "the boundary state was sampled during the shunting run"
		).isTrue()
		assertNoInPlaceReversal()
		assertOriginWaitEntries()
	}
}
