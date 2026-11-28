/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.PlanningSnapshotLiveComparator
import cz.vutbr.fit.interlockSim.testutil.runSampled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get

/**
 * Issue #1468 (Goal 9B SP4.2, #1467 spec §7): every [SwitchReading] in the published snapshot equals
 * the live switch at the same tick — name, `RailSwitch.Conf`, locked and owning train.
 *
 * [PlanningSnapshotLiveComparator] is installed last, around the dispatcher's listener, so it is the
 * first code after publication (spec rule P3). Mismatches are asserted after the run.
 *
 * The run must also show the two cases the issue names, read from the verified published snapshots:
 * - a switch **locked by a route**: a reading with `locked` and an owning train;
 * - a switch **released behind a train**: a switch owned and locked by train T at one tick, free and
 *   unlocked at the next, while T is still an active train of that next snapshot and moved forward
 *   between the two ticks. This is a proxy, read from the snapshots only: it excludes a release by
 *   T's exit and by expiry of a standing train, but a reservation expiry or a
 *   `SwitchConfigurator.reclaimStaleForeignSwitchOwnership` while T moves would count as well. The
 *   live-equality check above holds for every reading, whatever released the switch.
 */
class SwitchReadingLiveEqualityTest : KoinTestBase() {
	private class Tally {
		var lockedByRoute = 0

		/** Releases while the owner was still active and moving: the proxy of the class KDoc. */
		var releasedWhileOwnerMoved = 0
		private var previous: Map<String, SwitchReading> = emptyMap()
		private var previousDistance: Map<String, Double> = emptyMap()

		fun record(snapshot: SimulationSnapshot) {
			val distance = snapshot.trainPositions.associate { it.trainId to it.totalDistance }
			for (reading in snapshot.switches) {
				if (reading.locked && reading.ownerTrainId != null) lockedByRoute++
				val before = previous[reading.name] ?: continue
				val owner = before.ownerTrainId
				val released = before.locked && owner != null && !reading.locked && reading.ownerTrainId == null
				if (released && ownerMoved(owner, distance)) releasedWhileOwnerMoved++
			}
			previous = snapshot.switches.associateBy { it.name }
			previousDistance = distance
		}

		private fun ownerMoved(
			owner: String,
			distance: Map<String, Double>
		): Boolean {
			val now = distance[owner] ?: return false
			val before = previousDistance[owner] ?: return false
			return now > before
		}
	}

	private fun runAndCompare(
		xml: String,
		trains: Int,
		maxConcurrent: Int?,
		switchLegalPairsOnly: Boolean,
		switchCount: Int
	) {
		val ctx: DefaultSimulationContext = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val loop =
			ShuntingLoop(
				ctx,
				END_TIME,
				maxConcurrentTrains = maxConcurrent,
				seed = SEED,
				maxTrains = trains,
				switchLegalPairsOnly = switchLegalPairsOnly
			)
		wireSynchronousDispatcher(ctx, loop)
		val dispatcherStep = checkNotNull(loop.controlStepListener)
		val tally = Tally()
		val comparator = PlanningSnapshotLiveComparator(ctx, loop)
		loop.controlStepListener =
			comparator.wrap(
				ControlStepListener {
					tally.record(loop.getLatestObservation().snapshot)
					dispatcherStep.onControlStep()
				}
			)
		ctx.setMainProcess(loop)
		runSampled(ctx) { }

		assertThat(comparator.mismatches, name = "switch snapshot vs live mismatches").isEmpty()
		assertThat(comparator.comparedTicks, name = "compared ticks").isGreaterThan(0)
		assertThat(comparator.comparedSwitchReadings, name = "compared switch readings")
			.isEqualTo(comparator.comparedTicks * switchCount)
		assertThat(tally.lockedByRoute, name = "readings of a switch locked by a route").isGreaterThan(0)
		assertThat(tally.releasedWhileOwnerMoved, name = "switches released while their owner moved on")
			.isGreaterThan(0)
	}

	@Test
	fun cervenyUjezdSwitchReadingsEqualTheLiveSwitchesAtEveryTick() {
		runAndCompare(
			NetworkResources.CERVENY_UJEZD_XML,
			trains = 3,
			maxConcurrent = null,
			switchLegalPairsOnly = false,
			switchCount = CU_SWITCHES
		)
	}

	@Test
	@Tag("integration-test")
	fun prahaSwitchReadingsEqualTheLiveSwitchesAtEveryTick() {
		runAndCompare(
			NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML,
			trains = 4,
			maxConcurrent = 4,
			switchLegalPairsOnly = true,
			switchCount = PRAHA_SWITCHES
		)
	}

	private companion object {
		const val END_TIME: Long = 600L
		const val SEED: Long = 1468L

		/**
		 * The same fact the desktop-ui fixture pin `CERVENY_UJEZD_SWITCHES` holds
		 * (`cz.vutbr.fit.interlockSim.testutil`, :desktop-ui's test/CervenyUjezdStructure.kt); a
		 * fixture edit that moves one pin moves both.
		 */
		const val CU_SWITCHES = 12
		const val PRAHA_SWITCHES = 50
	}
}
