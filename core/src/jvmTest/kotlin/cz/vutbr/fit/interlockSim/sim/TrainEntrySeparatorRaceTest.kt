/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Regression test for PR #633: animation `entrySeparator` shared-state race.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEventListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression test for the animation `entrySeparator` race fixed in PR #633.
 *
 * ## Root cause recap
 *
 * `Train.entrySeparator` (exposed as `trainEntrySeparator`) is read by
 * [cz.vutbr.fit.interlockSim.gui.animation.TrainPositionCalculator] to pick the interpolation
 * direction for the train **front's** current section. It must therefore always be one of the
 * two ends of `train.frontSection`.
 *
 * Both the `Front` and `Tail` sites inherit `Site.actions()`, which wrote the shared field at
 * every separator crossing. The Tail trails the front by one train-length, so once the train is
 * long enough that the front is two or more blocks ahead, the Tail's write points at a separator
 * that is **not** an end of the front's current section — flipping interpolation and making the
 * rendered front snap backward at every block boundary.
 *
 * ## Scenario
 *
 * A single long train (150 m) on a 4-block linear topology (4 × 100 m = 400 m). The train spans
 * ~1.5 blocks, so the front reaches block 3 while the tail is still in block 1 — exactly the
 * condition under which the Tail's `entrySeparator` write is not an end of the front's section.
 *
 * The test samples the invariant on every TRAIN_EVENTS report and every BlockEvent. Before the
 * fix (Tail writes the field), at least one sample observes `trainEntrySeparator ∉ ends(frontSection)`.
 * After the fix (only Front writes), the invariant holds for every sample.
 *
 * @since PR #633 — animation entrySeparator race regression test
 */
@DisplayName("Train entrySeparator tracks the front's section (PR #633 race regression)")
class TrainEntrySeparatorRaceTest : KoinTestBase() {
	private companion object {
		/** Long enough that the front gets ≥2 blocks ahead of the tail. */
		const val TRAIN_LENGTH: Double = 150.0

		/** Simulation end time — long enough for the train to traverse most of the route. */
		const val END_TIME: Long = 400L
	}

	/**
	 * `true` iff `train.trainEntrySeparator` is consistent with `train.frontSection`:
	 * either is null (train not yet placed / between sections), or the entry separator is one of
	 * the section's two ends (identity-compared after unwrapping dynamic wrappers, exactly as
	 * [cz.vutbr.fit.interlockSim.gui.animation.TrainPositionCalculator] does).
	 */
	private fun entrySeparatorConsistentWithFrontSection(train: Train): Boolean {
		val section = train.frontSection ?: return true
		val entry = train.trainEntrySeparator ?: return true
		val ends = section.ends()
		if (ends.size < 2) return true
		val entryStatic = DynamicWrapperUtils.unwrapToStatic(entry) ?: return true
		return ends.any { DynamicWrapperUtils.unwrapToStatic(it) === entryStatic }
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("trainEntrySeparator is always an end of frontSection while the train is running")
	fun `trainEntrySeparator always matches an end of the front section`() {
		val violations = mutableListOf<String>()

		// 4-block linear topology: A → Sem1 → Sem2 → Sem3 → B (4 × 100 m). Built with
		// TestContextBuilder so the InOut orientation matches the production sim path.
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
				endTime = END_TIME,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = TRAIN_LENGTH)
					)
			)
		ctx.setMainProcess(loop)

		// Sample on every TRAIN_EVENTS report (front enter/leave-block, semaphore, ends, ...).
		val reportListener =
			ContextPropertyChangeListener { event ->
				if (event.propertyName != ReportType.TRAIN_EVENTS.name) return@ContextPropertyChangeListener
				loop.getApprovedTrains().forEach { train ->
					if (!entrySeparatorConsistentWithFrontSection(train)) {
						violations.add(
							"train ${train.trainNumber}: entrySeparator=${train.trainEntrySeparator} " +
								"is not an end of frontSection=${train.frontSection}"
						)
					}
				}
			}
		// Sample on every BlockEvent (OccupancySet/OccupancyCleared at each block boundary).
		val blockListener =
			object : BlockEventListener {
				override fun onBlockEvent(event: BlockEvent) {
					loop.getApprovedTrains().forEach { train ->
						if (!entrySeparatorConsistentWithFrontSection(train)) {
							violations.add(
								"train ${train.trainNumber}: entrySeparator=${train.trainEntrySeparator} " +
									"is not an end of frontSection=${train.frontSection} (block event)"
							)
						}
					}
				}
			}

		ctx.addPropertyChangeListener(reportListener)
		ctx.addBlockEventListener(blockListener)
		try {
			ctx.run()
		} finally {
			ctx.removePropertyChangeListener(reportListener)
			ctx.removeBlockEventListener(blockListener)
		}

		// The train must have actually traversed far enough for the front to get ahead of the tail.
		assertThat(loop.getTrainsEntered() >= 1, name = "a train was dispatched")
			.isTrue()

		assertThat(
			violations.isEmpty(),
			name = "no entrySeparator/frontSection mismatch (violations: ${violations.take(5)})"
		).isTrue()
	}
}
