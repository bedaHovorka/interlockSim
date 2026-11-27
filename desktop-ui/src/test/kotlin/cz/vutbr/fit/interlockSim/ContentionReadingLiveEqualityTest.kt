/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.ContentionQuery
import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.ports.ContentionReading
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import cz.vutbr.fit.interlockSim.util.Array2DMap
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Issue #1470 (Goal 9B SP4.4, #1467 spec §7): each [ContentionReading] in the published
 * [SimulationSnapshot] equals the live [ContentionQuery] state at the same tick, on the rule-based
 * control arm, where trains really wait for each other.
 *
 * The example is built from [ExampleRegistry] with all its wiring; then the control-step listener is
 * wrapped (the `PlanningSnapshotLiveComparator` decorator style: installed last, so under spec rule P3
 * it is the first code after publication). At every control step, before the dispatcher's own
 * listener drains any command, it compares [SimulationSnapshot.contentions] of the published
 * observation, entry by entry and in order, with `ContentionQuery.openContentions()` read live on the
 * simulation thread:
 * - [ContentionReading.waitingTrainId] with [OpenContention.trainId];
 * - [ContentionReading.sectionId] with the grid id of the live [OpenContention.block], computed from
 *   the grid locations of `block.ends()` (an oracle independent of the port's graph scan);
 * - [ContentionReading.ownerTrainId] with [OpenContention.owner];
 * - [ContentionReading.sinceSimTime] with [OpenContention.since].
 *
 * Non-vacuous on Červený Újezd: the run must reach the two-train wait state the issue names, a tick
 * on which a train waits for a section another train holds (its control arm has one failed
 * reservation per run, `ControlArmGoldenOutcomeTest`). The Praha control arm never refuses a
 * reservation (0 failed reservations on every golden seed), so there the test pins only that the
 * facet equals the live query, empty included, at every tick. Mismatches are collected and asserted after the run: a
 * throw inside the listener would end the run (`MainProcessFailureRunEndTest`).
 *
 * @since Issue #1470 (Goal 9B SP4.4)
 */
@DisplayName("Snapshot contentions equal the live ContentionQuery at every control step")
class ContentionReadingLiveEqualityTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun liveSectionId(
		ctx: DefaultSimulationContext,
		block: DynamicTrackBlock
	): String {
		val grid = ctx.getRailWayNetGrid()
		return block
			.ends()
			.map { end -> checkNotNull(grid.getLocation(end as Cell)) { "end $end of a graph block has no grid location" } }
			.sortedWith(Array2DMap.POINT_COMPARATOR)
			.joinToString("-") { "(${it.x},${it.y})" }
	}

	private fun runAndCompare(
		exampleName: String,
		seed: String?,
		requireTwoTrainWait: Boolean
	) {
		val ctx = buildContext(exampleName, seed)
		val loop = ctx.mainProcess as ShuntingLoop
		val contentionQuery: ContentionQuery = ctx.getRoutingServices().getContentionQuery()
		val dispatcherStep = checkNotNull(loop.controlStepListener) { "$exampleName wires no control-step listener" }
		val mismatches = mutableListOf<String>()
		var comparedTicks = 0
		var waitsForAnotherTrain = 0
		loop.controlStepListener =
			ControlStepListener {
				val observation = loop.getLatestObservation()
				val published = observation.snapshot.contentions
				val live = contentionQuery.openContentions()
				if (published.size != live.size) {
					mismatches += "tick ${observation.tick}: ${published.size} readings for ${live.size} open contentions"
				}
				published.zip(live).forEachIndexed { index, (reading, contention) ->
					val expected =
						ContentionReading(
							waitingTrainId = contention.trainId,
							sectionId = liveSectionId(ctx, contention.block),
							ownerTrainId = contention.owner,
							sinceSimTime = contention.since
						)
					if (reading != expected) {
						mismatches += "tick ${observation.tick} #$index: $reading != live $expected"
					}
				}
				waitsForAnotherTrain += live.count { it.owner != null && it.owner != it.trainId }
				comparedTicks++
				dispatcherStep.onControlStep()
			}

		ctx.run()

		assertThat(mismatches, name = "snapshot vs live mismatches").isEmpty()
		assertThat(comparedTicks, name = "compared ticks").isGreaterThan(0)
		if (requireTwoTrainWait) {
			assertThat(waitsForAnotherTrain, name = "ticks x contentions where a train waits for another").isGreaterThan(0)
		}
	}

	/** Builds [exampleName] from the CLI map [ExampleRegistry.examples], as `example <name> 600 [seed]` does. */
	private fun buildContext(
		exampleName: String,
		seed: String?
	): DefaultSimulationContext {
		val factory = checkNotNull(get<ExampleRegistry>().examples[exampleName]) { "$exampleName: not registered" }
		val args = listOfNotNull("example", exampleName, END_TIME, seed).toTypedArray()
		return (factory(get(), args) as DefaultSimulationContext).tracked()
	}

	@Test
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	fun `cervenyUjezd snapshot contentions equal the live contention query at every tick`() {
		runAndCompare(ExampleNames.CERVENY_UJEZD, seed = null, requireTwoTrainWait = true)
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	fun `prahaHlavniNadrazi snapshot contentions equal the live contention query at every tick`() {
		runAndCompare(ExampleNames.PRAHA_HLAVNI_NADRAZI, seed = PRAHA_SEED, requireTwoTrainWait = false)
	}

	private companion object {
		const val END_TIME = "600"

		/** The SP10 reference seed of the Praha control arm (`ControlArmGoldenOutcomeTest`). */
		const val PRAHA_SEED = "1366"
	}
}
