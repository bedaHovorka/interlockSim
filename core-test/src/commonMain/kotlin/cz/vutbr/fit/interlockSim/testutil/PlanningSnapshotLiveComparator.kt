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
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.SwitchReading
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ProvidesDispatchLoopObservation
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
 * [PathReservationRegistry.getSwitchOwner]. Later SP4 leaves (#1469, #1470, #1472) add their facets here.
 *
 * @param context the simulation context whose live state is compared; its scope serves the registry
 * @param observation the publisher of the planning snapshot, normally the `ShuntingLoop`
 */
class PlanningSnapshotLiveComparator(
	private val context: SimulationContext,
	private val observation: ProvidesDispatchLoopObservation
) {
	private val registry: PathReservationRegistry by lazy { context.scope.get<PathReservationRegistry>() }

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

	/** Returns a listener that compares the tick's snapshot with live state, then calls [delegate]. */
	fun wrap(delegate: ControlStepListener?): ControlStepListener =
		ControlStepListener {
			compare(observation.latestDispatchLoopSnapshot())
			delegate?.onControlStep()
		}

	private fun compare(published: DispatchLoopSnapshot) {
		compareSwitches(published.tick, published.snapshot.switches)
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
}
