/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test utility: compare the published planning snapshot with live simulation state
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.ContentionQuery
import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.ports.ContentionReading
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.SwitchReading
import cz.vutbr.fit.interlockSim.ports.TrainMotionState
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.ApprovesTrains
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ProvidesDispatchLoopObservation
import cz.vutbr.fit.interlockSim.util.Array2DMap
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.GeneratedElementName

/**
 * Compares each published planning snapshot with the live simulation state it was sampled from
 * (#1467 spec §7, Issue #1468).
 *
 * `ShuntingLoop.controlStepListener` is a single slot that `wireSynchronousDispatcher` and the
 * `SteeredPickObserver` wiring overwrite, so the comparator is a decorator: [wrap] returns a
 * [ControlStepListener] that first compares [ProvidesDispatchLoopObservation.latestDispatchLoopSnapshot]
 * with the live sources, re-read on the simulation thread, and then calls the delegate. Install it
 * after all other wiring: under spec rule P3 it is then the first code to run after publication, so
 * live state equals the snapshot of the tick.
 *
 * Mismatches are collected, not thrown: a throw inside the listener would end the run
 * (`MainProcessFailureRunEndTest`). Assert [mismatches] after the run.
 *
 * Facets compared today: the switches (#1468): name ([GeneratedElementName.switchName] of the grid
 * position for a blank name), `DynamicRailSwitch.conf`, `DynamicRailSwitch.locked` and
 * [PathReservationRegistry.getSwitchOwner]; the open contentions (#1470): entry by entry and in order
 * against [ContentionQuery.openContentions] — waiting train, section id (the grid id of the live
 * block, computed from the grid locations of its ends, an oracle independent of the port's graph
 * scan), owning train and first-observed time; the train sample (#1472): the train list by id and
 * in order against [ApprovesTrains.getApprovedTrains], then every [TrainPositionReading] field against
 * [TrainSampleOracle.liveSample], doubles exactly. Later SP4 leaves (#1469) add their facets here.
 *
 * @param context the simulation context whose live state is compared; its scope serves the registry
 * @param observation the publisher of the planning snapshot, normally the `ShuntingLoop`
 * @param approvedTrains the live trains the train sample is compared with; by default [observation]
 *   itself when it approves trains (the `ShuntingLoop` does), else the train facet is not compared
 */
class PlanningSnapshotLiveComparator(
	private val context: SimulationContext,
	private val observation: ProvidesDispatchLoopObservation,
	private val approvedTrains: ApprovesTrains? = observation as? ApprovesTrains
) {
	private val registry: PathReservationRegistry by lazy { context.scope.get<PathReservationRegistry>() }

	private val contentionQuery: ContentionQuery by lazy { context.getRoutingServices().getContentionQuery() }

	/** Every live switch with its expected snapshot name, scanned once: cells never change at runtime. */
	private val liveSwitches: List<Pair<String, DynamicRailSwitch>> by lazy {
		val grid = context.getRailWayNetGrid()
		buildList {
			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
					val switch = grid.getCellAt(x, y) as? DynamicRailSwitch ?: continue
					add(GeneratedElementName.switchName(switch.name, Point(x, y)) to switch)
				}
			}
		}
	}

	private val collected = mutableListOf<String>()

	/** Every snapshot-versus-live difference seen so far, one line each, prefixed by the tick. */
	val mismatches: List<String> get() = collected.toList()

	/** The number of published snapshots compared so far. */
	var comparedTicks: Int = 0
		private set

	/** The number of switch readings compared so far (all ticks). */
	var comparedSwitchReadings: Int = 0
		private set

	/** The number of contention readings compared so far (all ticks). */
	var comparedContentionReadings: Int = 0
		private set

	/**
	 * Ticks x live contentions in which a train waits for a section another train holds, summed over
	 * all ticks: the two-train wait state a non-vacuous contention check needs (#1470).
	 */
	var contentionsWaitingForAnotherTrain: Int = 0
		private set

	/** The number of train readings compared so far (all ticks). */
	var comparedTrainReadings: Int = 0
		private set

	private val statesSeen = mutableSetOf<TrainMotionState?>()

	/** Every [TrainPositionReading.state] seen in a compared train sample. */
	val trainStatesSeen: Set<TrainMotionState?> get() = statesSeen.toSet()

	/** Returns a listener that compares the tick's snapshot with live state, then calls [delegate]. */
	fun wrap(delegate: ControlStepListener?): ControlStepListener =
		ControlStepListener {
			compare(observation.latestDispatchLoopSnapshot())
			delegate?.onControlStep()
		}

	private fun compare(published: DispatchLoopSnapshot) {
		compareSwitches(published.tick, published.snapshot.switches)
		compareContentions(published.tick, published.snapshot.contentions)
		compareTrains(published.tick, published.snapshot.trainPositions)
		comparedTicks++
	}

	private fun compareSwitches(
		tick: Long,
		readings: List<SwitchReading>
	) {
		val byName = readings.associateBy { it.name }
		if (byName.size != readings.size) collected += "tick $tick: duplicate switch names in ${readings.map { it.name }}"
		if (readings.size != liveSwitches.size) {
			collected += "tick $tick: ${readings.size} switch readings for ${liveSwitches.size} live switches"
		}
		for ((name, switch) in liveSwitches) {
			val reading = byName[name]
			if (reading == null) {
				collected += "tick $tick: no reading for switch $name"
				continue
			}
			val live = SwitchReading(name, switch.conf, switch.locked, registry.getSwitchOwner(switch))
			if (reading != live) collected += "tick $tick: switch $name published $reading != live $live"
			comparedSwitchReadings++
		}
	}

	private fun compareContentions(
		tick: Long,
		readings: List<ContentionReading>
	) {
		val live = contentionQuery.openContentions()
		if (readings.size != live.size) collected += "tick $tick: ${readings.size} readings for ${live.size} open contentions"
		readings.zip(live).forEachIndexed { index, (reading, contention) ->
			val expected = contention.toReading()
			if (reading != expected) collected += "tick $tick #$index: contention $reading != live $expected"
			comparedContentionReadings++
		}
		contentionsWaitingForAnotherTrain += live.count { it.owner != null && it.owner != it.trainId }
	}

	private fun compareTrains(
		tick: Long,
		sample: List<TrainPositionReading>
	) {
		val live = approvedTrains ?: return
		collected += TrainSampleOracle.compareWithLive(tick, sample, live.getApprovedTrains())
		comparedTrainReadings += sample.size
		statesSeen += sample.map { it.state }
	}

	private fun OpenContention.toReading(): ContentionReading =
		ContentionReading(
			waitingTrainId = trainId,
			sectionId = liveSectionId(block),
			ownerTrainId = owner,
			sinceSimTime = since
		)

	private fun liveSectionId(block: DynamicTrackBlock): String {
		val grid = context.getRailWayNetGrid()
		return block
			.ends()
			.map { end -> checkNotNull(grid.getLocation(end as Cell)) { "end $end of a graph block has no grid location" } }
			.sortedWith(Array2DMap.POINT_COMPARATOR)
			.joinToString("-") { "(${it.x},${it.y})" }
	}
}
