/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */

/**
 * Dispatcher observation model (SP2c.1, #824).
 *
 * The canonical push-based snapshot published each tick by
 * [DispatcherObservationProjector] and consumed by the control loop and renderers.
 * All types in this package are immutable, deterministically ordered data classes
 * — see [DispatcherObservation] for the full contract.
 */
package cz.vutbr.fit.interlockSim.dispatcher.observation

import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import java.security.MessageDigest

/**
 * Lifecycle phase of one train as seen by the dispatcher control loop (SP2c.1, #824).
 *
 * - [QUEUED] — generated but not yet approved (admitted) onto the network.
 * - [RUNNING] — approved and moving (`velocityMps > 0`).
 * - [DWELLING] — approved, stopped, and the stop is a **commanded station dwell**
 *   ([cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading.isStationDwelling]).
 * - [HELD] — approved, stopped, and the stop is **not** a commanded station dwell —
 *   typically blocked at a `STOP` signal awaiting a path.
 * - [EXITED] — was queued or active on the previous captured tick and is present in
 *   neither list this tick. Emitted exactly once, the tick the train disappears, using
 *   its last known [TrainView] fields (see [DispatcherObservationProjector] KDoc).
 */
enum class TrainPhase { QUEUED, RUNNING, DWELLING, HELD, EXITED }

/**
 * One train's state as pushed to the dispatcher on one tick.
 *
 * @property trainId Train identifier (e.g. `"Train #1"`).
 * @property phase Current lifecycle phase, see [TrainPhase].
 * @property frontSectionName Block currently containing the train's front, or `null` if
 *   not yet entered (queued trains, or a train just approved this tick).
 * @property velocityMps Current speed in m/s. `0.0` for queued/exited trains.
 * @property accelerationMps2 Current acceleration in m/s². `0.0` for queued/exited trains.
 * @property destinationInOutName Name of the InOut the train aims to reach on this leg.
 * @property signalAheadName Name of the next semaphore ahead, or `null` if no path is set.
 * @property signalAheadAspect [Signal] aspect of the next semaphore ahead, or `null`.
 * @property distanceToSignalAheadMetres Distance from the train's front to the next semaphore.
 * @property waitingSinceSimTime Simulation time this train started its **current** wait
 *   ([TrainPhase.QUEUED] or [TrainPhase.HELD]/[TrainPhase.DWELLING]), or `null` when not
 *   currently waiting (running, or exited). See [DispatcherObservationProjector] for the
 *   statefulness this requires.
 * @property waitSeconds `simTime - waitingSinceSimTime`, or `0.0` when not currently waiting.
 */
data class TrainView(
	val trainId: String,
	val phase: TrainPhase,
	val frontSectionName: String?,
	val velocityMps: Double,
	val accelerationMps2: Double,
	val destinationInOutName: String,
	val signalAheadName: String?,
	val signalAheadAspect: Signal?,
	val distanceToSignalAheadMetres: Double,
	val waitingSinceSimTime: Double?,
	val waitSeconds: Double
)

/**
 * One track block's occupancy state on one tick.
 *
 * @property blockId Stable block identifier ([cz.vutbr.fit.interlockSim.util.BlockIdentity.stableBlockId]).
 * @property state Current occupancy state.
 * @property occupantTrainId Train that owns (reserved or occupies) this block, `null` when [state]
 *   is [TrackFacility.State.FREE].
 */
data class BlockView(
	val blockId: String,
	val state: TrackFacility.State,
	val occupantTrainId: String?
)

/**
 * One switch's dynamic state on one tick.
 *
 * @property switchName Switch name as configured in the railway XML.
 * @property position Current [RailSwitch.Conf] (`MAIN`/`BRANCH`).
 * @property lockedByTrainId Train that currently owns/locks this switch
 *   ([cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.getSwitchOwner]),
 *   or `null` when unlocked.
 */
data class SwitchView(
	val switchName: String,
	val position: RailSwitch.Conf,
	val lockedByTrainId: String?
)

/**
 * One semaphore's aspect on one tick.
 *
 * @property name Semaphore name.
 * @property aspect Current [Signal] indication.
 * @property authorizedFrom Segment name travel is authorized from, or `null` when not allowing.
 * @property authorizedTo Segment name travel is authorized to, or `null` when not allowing.
 */
data class SignalView(
	val name: String,
	val aspect: Signal,
	val authorizedFrom: String?,
	val authorizedTo: String?
)

/**
 * One train's active path reservation on one tick.
 *
 * @property trainId Owning train identifier.
 * @property fromEndpointName Name of the separator the reservation starts from, or `null` if
 *   the underlying [cz.vutbr.fit.interlockSim.objects.paths.PathInfo.start] is a separator type
 *   this projection does not name (defensive; every separator type in `vyhybna.xml` is named).
 * @property targetName Name of the separator the reservation is set toward. Empty string in the
 *   same defensive case as [fromEndpointName] — never `null`, matching the non-nullable contract
 *   [cz.vutbr.fit.interlockSim.objects.paths.PathInfo.target] itself guarantees (a reservation
 *   always has a target).
 * @property blockIds Reserved blocks **in physical route order** (start to target). This list is
 *   deliberately **not** alphabetically sorted like the other [DispatcherObservation] lists — it
 *   is backed by [cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.getBlocks],
 *   itself an insertion-ordered `List` (not a `Set`), so it is already deterministic for a given,
 *   deterministic simulation run; re-sorting it alphabetically would destroy route-order
 *   information for no reproducibility gain.
 */
data class ReservationView(
	val trainId: String,
	val fromEndpointName: String?,
	val targetName: String,
	val blockIds: List<String>
)

/**
 * One queued (not yet approved) train on one tick.
 *
 * @property trainId Train identifier.
 * @property destinationInOutName Name of the InOut this train aims to reach once admitted.
 * @property queuedSinceSimTime Simulation time this train first appeared in the queue. See
 *   [DispatcherObservationProjector] for the statefulness this requires — this value is not
 *   carried by [cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation] itself.
 */
data class QueuedTrainView(
	val trainId: String,
	val destinationInOutName: String,
	val queuedSinceSimTime: Double
)

/**
 * Correlated outcome of one applied actuator command, pushed into the next tick's perception
 * prompt so the agent always learns what actually happened — closing the fire-and-forget gap
 * documented in SP2c.17 (#840).
 *
 * Every outcome carries a [CommandId] that was issued at queue-post time (driver thread) and
 * looked up by identity in [cz.vutbr.fit.interlockSim.dispatcher.CommandCorrelationMap] on the
 * sim thread when the decision was applied. This one-to-one correlation is how the agent can
 * connect "I issued `request_route T-087 -> InOut-B`" to "that request was CONFLICTED because
 * block W2 is held by T-042".
 *
 * ## tickIndex attribution
 *
 * [tickIndex] is the [CommandCorrelationMap.newCycle] cycle counter value at the time the
 * command was posted — a diagnostic stamp of the originating driver cycle. It is **not** used
 * by [DispatcherObservationProjector] to filter draining: next-tick semantics come from the
 * call order in `ShuntingLoop.iteration()` (capture runs before the control step that publishes
 * new outcomes), so the projector drains the whole ring each tick. [tickIndex] remains
 * available for consumers to detect gaps via [CommandId] discontinuities.
 *
 * @property id Correlation key issued at queue-post time.
 * @property tickIndex Driver-thread cycle counter value at the time the command was posted.
 *
 * @since Issue #840 (SP2c.17 — correlated async outcome channel)
 */
sealed interface AppliedOutcome {
	val id: CommandId
	val tickIndex: Long

	/** `request_route` succeeded; the full path from [fromEndpointName] to [toEndpointName] is now locked. */
	data class Reserved(
		val trainId: String,
		val fromEndpointName: String,
		val toEndpointName: String,
		val blocksCount: Int,
		override val id: CommandId,
		override val tickIndex: Long
	) : AppliedOutcome

	/** `request_route` failed because all paths between the endpoints were physically blocked. */
	data class Blocked(
		val trainId: String,
		val fromEndpointName: String,
		val toEndpointName: String,
		val attemptedPaths: Int,
		override val id: CommandId,
		override val tickIndex: Long
	) : AppliedOutcome

	/** `request_route` failed because a competing train holds a block on every candidate path. */
	data class Conflicted(
		val trainId: String,
		val fromEndpointName: String,
		val toEndpointName: String,
		/** Name of the conflicting block, or `null` when not determinable from the result. */
		val blockName: String?,
		val existingOwner: String,
		override val id: CommandId,
		override val tickIndex: Long
	) : AppliedOutcome

	/** `request_route` failed because the station topology contains no path between the endpoints. */
	data class NoRoute(
		val trainId: String,
		val fromEndpointName: String,
		val toEndpointName: String,
		override val id: CommandId,
		override val tickIndex: Long
	) : AppliedOutcome

	/**
	 * `request_route` failed because the requested origin is not contiguous with the train's own
	 * position: it bounds none of the blocks the train holds or occupies, so the train could
	 * never have reached the route (Issue #893).
	 *
	 * Unlike [Blocked], retrying the identical request is futile while the train stays put — the
	 * agent has to name a different origin, which is why [reason] is carried verbatim from
	 * `DefaultPathReservationService`: it already names the offending origin and every legal
	 * alternative for this train.
	 *
	 * @since Issue #893 (phase alpha, task A-R1)
	 */
	data class OriginNotContiguous(
		val trainId: String,
		val fromEndpointName: String,
		val toEndpointName: String,
		/** English explanation from the reservation kernel, naming the legal origins. */
		val reason: String,
		override val id: CommandId,
		override val tickIndex: Long
	) : AppliedOutcome

	/** `cancel_route` completed; [anyReleased] is `true` if at least one block was released. */
	data class Released(
		val trainId: String,
		val anyReleased: Boolean,
		override val id: CommandId,
		override val tickIndex: Long
	) : AppliedOutcome

	/**
	 * `approve_train` was processed at apply time on the simulation thread.
	 *
	 * - [admitted] = `true`, [reason] = `null`: the cap check passed and the admission callback
	 *   (`ShuntingLoop.approveQueuedTrain`) was invoked. The callback itself is idempotent — an
	 *   absent or already-active train is silently ignored — so `admitted = true` means the
	 *   command was applied, not necessarily that a new train entered the sim.
	 * - [admitted] = `false`, [reason] = [ApplyFailureCode.CAP_EXCEEDED_APPLY]: the station was
	 *   already at full capacity when this command was drained.  The callback was **not**
	 *   invoked.  The agent should wait for an active train to exit before retrying.
	 *
	 * ## SP2c.18 note
	 *
	 * Prior to SP2c.18, [admitted] was always `true` (the applier never checked the cap).
	 * SP2c.18 moves the authoritative cap check onto the sim thread so two commands posted
	 * in the same driver tick that both passed the stale-snapshot [ActionValidator] check
	 * are resolved correctly in FIFO order: the first is admitted, the second receives
	 * [ApplyFailureCode.CAP_EXCEEDED_APPLY].
	 */
	data class Approved(
		val trainId: String,
		val admitted: Boolean,
		/**
		 * Non-null when [admitted] is `false`, carries the machine-readable reason.
		 * Always `null` when [admitted] is `true`.
		 *
		 * @since Issue #841 (SP2c.18 — Goal 10 apply-time cap enforcement)
		 */
		val reason: ApplyFailureCode? = null,
		override val id: CommandId,
		override val tickIndex: Long
	) : AppliedOutcome

	/**
	 * The decision was dropped because [NetworkActuatorPort] threw an
	 * [IllegalArgumentException] (e.g. the LLM hallucinated an endpoint name that
	 * doesn't exist in this station). The command was **not** applied.
	 */
	data class DroppedInvalid(
		/** Tool name that produced the command (e.g. `"request_route"`, `"cancel_route"`). */
		val commandType: String,
		val trainId: String,
		val message: String,
		override val id: CommandId,
		override val tickIndex: Long
	) : AppliedOutcome
}

/**
 * One immutable, deterministic, sim-thread-captured snapshot of everything the Goal 10
 * dispatcher control loop needs to render, annotate, and validate the next decision (SP2c.1,
 * #824). This replaces query-tool-based perception (nine tools, #822 constraint C1) and the
 * stale-snapshot problem (#822 root cause RC2) with a single pushed value.
 *
 * ## Sorting (P8 reproducibility)
 *
 * Every entity list ([trains], [blocks], [switches], [signals], [reservations], [queued]) is
 * sorted by its documented natural key (see each view type's KDoc) by
 * [DispatcherObservationProjector] before publication. Unsorted collections are — per #824's own
 * wording — "the classic silent source of non-reproducible prompts": a `HashMap`/`HashSet`-backed
 * iteration order is a JVM implementation detail, not a simulation invariant, and would make two
 * byte-identical simulation runs render different prompts. [ReservationView.blockIds] is the one
 * deliberate exception — see its KDoc.
 *
 * @property tick Monotonically increasing publish-sequence number, incremented once per
 *   [DispatcherObservationProjector.captureOnSimThread] call. `0` for [EMPTY].
 * @property simTime Simulation time (seconds) this observation was captured at.
 * @property trains Every queued, active, or just-exited train. Sorted by [TrainView.trainId].
 * @property blocks Every track block's occupancy. Sorted by [BlockView.blockId].
 * @property switches Every named switch's position and lock owner. Sorted by [SwitchView.switchName].
 * @property signals Every semaphore's aspect. Sorted by [SignalView.name].
 * @property reservations Every active path reservation. Sorted by [ReservationView.trainId].
 * @property queued Every train waiting for admission. Sorted by [QueuedTrainView.trainId].
 * @property activeCount Number of currently approved (active) trains —
 *   `snapshot.trainPositions.size`, identical to
 *   [cz.vutbr.fit.interlockSim.sim.DispatchObservation.approvedTrainCount].
 * @property capacity Station capacity (maximum concurrently active trains). Defaults to
 *   [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS], the same constant
 *   [cz.vutbr.fit.interlockSim.sim.ShuntingLoop]'s hard admission gate, `approve_train`'s tool
 *   guard, and the (now-deleted per #822 §6) admission safety net all used, so every consumer of
 *   "capacity" in this codebase agrees by default.
 * @property appliedOutcomes Outcomes of previously queued actuator commands correlated back to
 *   this tick. Populated by [DispatcherObservationProjector] when an
 *   [cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeChannel] is wired (SP2c.17, #840).
 *   Always empty when no channel is wired (headless / rule-based-only runs).
 * @property innerBlockInputs Directional block-input observations for all **inner** track blocks
 *   (RailSemaphore–RailSemaphore bounded). Populated by [DispatcherObservationProjector] from
 *   [cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort.snapshot] on each sim tick.
 *   Consumed by [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedEmissionStrategy] to convert a
 *   [DispatcherObservation] back into a
 *   [cz.vutbr.fit.interlockSim.sim.DispatchObservation] for [cz.vutbr.fit.interlockSim.sim.Dispatcher.decide].
 *   Defaults to [emptyList] so existing callers that pre-date SP2c.5 are unaffected.
 * @property outerBlockInputs Directional block-input observations for all **outer** track blocks
 *   (InOut–RailSemaphore bounded). Same contract as [innerBlockInputs].
 *
 * @since Issue #824 (SP2c.1 — Goal 10 autonomous dispatcher control-loop redesign);
 *   [innerBlockInputs] / [outerBlockInputs] added in Issue #828 (SP2c.5 — DispatchTickLoop)
 */
data class DispatcherObservation(
	val tick: Long,
	val simTime: Double,
	val trains: List<TrainView>,
	val blocks: List<BlockView>,
	val switches: List<SwitchView>,
	val signals: List<SignalView>,
	val reservations: List<ReservationView>,
	val queued: List<QueuedTrainView>,
	val activeCount: Int,
	val capacity: Int,
	val appliedOutcomes: List<AppliedOutcome>,
	/**
	 * Directional block-input observations for all **inner** track blocks. Populated by
	 * [DispatcherObservationProjector] from [cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort]
	 * on each sim tick (SP2c.5, Issue #828). Defaults to [emptyList] for backwards compatibility
	 * with callers that pre-date SP2c.5 (renderers, tests, the SP2c.1–4 scaffolding).
	 */
	val innerBlockInputs: List<BlockInputObservation> = emptyList(),
	/**
	 * Directional block-input observations for all **outer** track blocks. Same contract as
	 * [innerBlockInputs].
	 */
	val outerBlockInputs: List<BlockInputObservation> = emptyList()
) {
	/**
	 * Stable content hash of this observation, suitable as the ring-buffer digest key (#822
	 * §5.1/C5). Two [DispatcherObservation] values that are structurally [equals] always produce
	 * the same digest, and the reverse holds in practice (SHA-256 over the canonical [toString]):
	 * a Kotlin data class [toString] enumerates every property, in declaration order, using each
	 * nested value's own (equally deterministic) `toString` — so nothing in this value's shape
	 * can change without changing the digest.
	 */
	fun digest(): String {
		val bytes = MessageDigest.getInstance("SHA-256").digest(toString().toByteArray(Charsets.UTF_8))
		return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	companion object {
		/**
		 * Safe default published by [DispatcherObservationProjector.latest] before the first
		 * [DispatcherObservationProjector.captureOnSimThread] call. Carries no live state — every
		 * list is empty, `tick` is `0`, `activeCount` is `0` — so a well-behaved consumer reads it
		 * as "nothing to do yet", the same contract [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot.EMPTY]
		 * and [cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot.EMPTY] already establish for
		 * their respective sense values.
		 */
		val EMPTY: DispatcherObservation =
			DispatcherObservation(
				tick = 0L,
				simTime = 0.0,
				trains = emptyList(),
				blocks = emptyList(),
				switches = emptyList(),
				signals = emptyList(),
				reservations = emptyList(),
				queued = emptyList(),
				activeCount = 0,
				capacity = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS,
				appliedOutcomes = emptyList()
			)
	}
}

/**
 * The **only** type downstream code (renderers, annotators, validators — SP2c.2+) may depend on
 * for dispatcher perception (#824 acceptance criterion). Never depend on
 * [DispatcherObservationProjector] directly outside its own wiring/tests: depending on this
 * narrower interface instead is what lets a future compressed Praha projection
 * ([cz.vutbr.fit.interlockSim.dispatcher.observation], #822 §10) be substituted later without
 * touching any consumer.
 */
fun interface DispatcherObservationSource {
	/**
	 * Returns the most recently published [DispatcherObservation]. Off-thread-safe: safe to call
	 * from any thread, including the agent-driver thread, at any time. Never blocks, never throws,
	 * and never reads live simulation state directly — it returns whatever
	 * [DispatcherObservationProjector.captureOnSimThread] last published, or
	 * [DispatcherObservation.EMPTY] before the first publish.
	 */
	fun latest(): DispatcherObservation
}
