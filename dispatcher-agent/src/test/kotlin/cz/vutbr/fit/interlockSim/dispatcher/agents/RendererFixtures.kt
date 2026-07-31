/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.ValidationVerdict
import cz.vutbr.fit.interlockSim.dispatcher.observation.BlockView
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.QueuedTrainView
import cz.vutbr.fit.interlockSim.dispatcher.observation.ReservationView
import cz.vutbr.fit.interlockSim.dispatcher.observation.SignalView
import cz.vutbr.fit.interlockSim.dispatcher.observation.SwitchView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher

/**
 * Shared test fixtures for renderer tests (SP2c.2, #825).
 *
 * All data mirrors the "tick 41" worked example from the SP2c.2 issue description.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
object RendererFixtures {
	/**
	 * The "tick 41" observation from the issue's worked example.
	 * T-3 is RUNNING at kA towards B; T-4 is QUEUED for B.
	 */
	val observationTick41: DispatcherObservation =
		DispatcherObservation(
			tick = 41L,
			simTime = 512.4,
			trains =
				listOf(
					TrainView(
						trainId = "T-3",
						phase = TrainPhase.RUNNING,
						frontSectionName = "kA",
						velocityMps = 6.4,
						accelerationMps2 = 0.4,
						destinationInOutName = "B",
						signalAheadName = "doB1",
						signalAheadAspect = Signal.STOP,
						distanceToSignalAheadMetres = 84.0,
						waitingSinceSimTime = null,
						waitSeconds = 0.0
					),
					TrainView(
						trainId = "T-4",
						phase = TrainPhase.QUEUED,
						frontSectionName = null,
						velocityMps = 0.0,
						accelerationMps2 = 0.0,
						destinationInOutName = "B",
						signalAheadName = null,
						signalAheadAspect = null,
						distanceToSignalAheadMetres = 0.0,
						waitingSinceSimTime = 498.0,
						waitSeconds = 14.4
					)
				),
			blocks =
				listOf(
					BlockView("kA", TrackFacility.State.OCCUPIED, "T-3"),
					BlockView("kB", TrackFacility.State.FREE, null),
					BlockView("kC", TrackFacility.State.FREE, null),
					BlockView("kD", TrackFacility.State.FREE, null),
					BlockView("kE", TrackFacility.State.FREE, null),
					BlockView("kF", TrackFacility.State.FREE, null),
					BlockView("kG", TrackFacility.State.RESERVED, "T-3"),
					BlockView("kH", TrackFacility.State.FREE, null),
					BlockView("kI", TrackFacility.State.FREE, null),
					BlockView("kJ", TrackFacility.State.RESERVED, "T-3")
				),
			switches =
				listOf(
					SwitchView("vA", RailSwitch.Conf.MAIN, null),
					SwitchView("vB", RailSwitch.Conf.MAIN, "T-3")
				),
			signals =
				listOf(
					SignalView("doA1", Signal.FREE, null, null),
					SignalView("doA2", Signal.STOP, null, null),
					SignalView("doB1", Signal.STOP, null, null),
					SignalView("doB2", Signal.STOP, null, null),
					SignalView("zA", Signal.STOP, null, null),
					SignalView("zB", Signal.STOP, null, null)
				),
			reservations =
				listOf(
					ReservationView("T-3", null, "B", listOf("kA", "kG", "kJ", "kB"))
				),
			queued =
				listOf(
					QueuedTrainView("T-4", "B", 498.0)
				),
			activeCount = 1,
			capacity = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS,
			appliedOutcomes = emptyList()
		)

	/**
	 * Previous tick (tick 40) observation — T-3 was HELD; kG and kJ were FREE.
	 */
	val observationTick40: DispatcherObservation =
		observationTick41.copy(
			tick = 40L,
			simTime = 504.8,
			trains =
				listOf(
					observationTick41.trains[0].copy(
						phase = TrainPhase.HELD,
						velocityMps = 0.0,
						accelerationMps2 = 0.0,
						waitingSinceSimTime = 501.0,
						waitSeconds = 3.8
					),
					observationTick41.trains[1]
				),
			blocks =
				observationTick41.blocks.map { bv ->
					when (bv.blockId) {
						"kG", "kJ" -> bv.copy(state = TrackFacility.State.FREE, occupantTrainId = null)
						else -> bv
					}
				},
			reservations = emptyList()
		)

	/** Three ring-buffer history records (ticks 38–40, before the current tick 41). */
	val history: List<TickRecord> =
		listOf(
			TickRecord(
				tick = 38L,
				simTime = 483.1,
				stateDigest = "a1b2c3d4e5f60000000000000000000000000000000000000000000000000000",
				actions = emptyList(),
				verdicts = emptyList(),
				outcomes = emptyList()
			),
			TickRecord(
				tick = 39L,
				simTime = 496.2,
				stateDigest = "f7e8d9c0b1a20000000000000000000000000000000000000000000000000000",
				actions = listOf(AttributedAction(CommandId(39L), 39L, DispatchAction.NoOp)),
				verdicts = listOf(ValidationVerdict.Valid),
				outcomes = emptyList()
			),
			TickRecord(
				tick = 40L,
				simTime = 504.8,
				stateDigest = "1a2b3c4d5e6f0000000000000000000000000000000000000000000000000000",
				actions = listOf(AttributedAction(CommandId(40L), 40L, DispatchAction.RequestRoute("T-3", "A", "B"))),
				verdicts = listOf(ValidationVerdict.Valid),
				outcomes = emptyList()
			)
		)

	/** Working memory for tick 41. */
	val workingMemory: WorkingMemory =
		WorkingMemory(
			pendingRequests = emptyMap(),
			lastActionPerTrain = mapOf("T-3" to Pair("request_route", 40L)),
			ticksSinceProgress = 0,
			activeCount = 1,
			capacity = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS
		)

	/** Pre-evaluated affordances for tick 41. */
	val affordances: List<Affordance> =
		listOf(
			Affordance("T-3", "approve_train", false, "train already active"),
			Affordance("T-3", "request_route", false, "already holds a route to B"),
			Affordance("T-3", "cancel_route", true, "would release kA,kG,kJ,kB"),
			Affordance("T-4", "approve_train", true, "1 of 2 slots free"),
			Affordance("T-4", "request_route", false, "no free path A->B"),
			Affordance("T-4", "cancel_route", false, "holds no route"),
			Affordance.NO_OP
		)

	/** The canonical tick-41 RenderContext. */
	val tick41Context: RenderContext =
		RenderContext(
			observation = observationTick41,
			previous = observationTick40,
			history = history,
			workingMemory = workingMemory,
			affordances = affordances
		)

	/** Tick-0 context: no previous observation, empty history. */
	val tick0Context: RenderContext =
		RenderContext(
			observation =
				DispatcherObservation.EMPTY.copy(
					tick = 0L,
					simTime = 0.0
				),
			previous = null,
			history = emptyList(),
			workingMemory = WorkingMemory.EMPTY,
			affordances = listOf(Affordance.NO_OP)
		)

	/** Minimal topology for a two-InOut station with two routes (A→B, B→A). */
	val minimalTopology: StationTopology =
		StationTopology(
			inOuts = listOf("A", "B"),
			signals = emptyList(),
			switches = emptyList(),
			blocks = listOf(BlockId("kA"), BlockId("kB")),
			routes =
				listOf(
					RouteDescriptor("A", "B", listOf(BlockId("kA"), BlockId("kB"))),
					RouteDescriptor("B", "A", listOf(BlockId("kB"), BlockId("kA")))
				)
		)
}
