/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import cz.vutbr.fit.interlockSim.objects.cells.Signal

/**
 * Stable read-only interface for agent perception of the railway network state.
 *
 * Provides the **sense** half of the agent control loop: an agent (LLM-driven or
 * algorithmic) queries this port to observe the current network state before deciding
 * what to do next.  All query methods return **immutable snapshot values** — they never
 * expose mutable simulation objects directly.
 *
 * ## Agent tools (SP1.6 / SP2a.1)
 *
 * Each method maps to one Koog tool in [SP1.6][Issue #551] or the SP2a.1 train
 * perception layer [Issue #552]:
 * - [signalAspect] — `tool_signal_aspect(semaphoreName)` → current [Signal] for one semaphore
 * - [allSignalAspects] — `tool_all_signal_aspects()` → all semaphore signals in one call
 * - [blockOccupancy] — `tool_block_occupancy(blockId)` → [BlockOccupancyReading] for one block
 * - [allBlockOccupancies] — `tool_all_block_occupancies()` → full occupancy map
 * - [trainPosition] — `tool_train_position(trainId)` → [TrainPositionReading] for one train
 * - [allTrainPositions] — `tool_all_train_positions()` → all active train positions
 * - [trainTimetable] — `tool_train_timetable(trainId)` → [TimetableReading] for one train
 * - [allTrainTimetables] — `tool_all_train_timetables()` → all active train timetables
 * - [trainPerception] — `tool_train_perception(trainId)` → [TrainPerceptionReading] for one train
 * - [allTrainPerceptions] — `tool_all_train_perceptions()` → all active train perceptions
 *
 * ## Design constraints
 *
 * - **Read-only**: this port exposes no mutation; for commands use the actuator port
 *   (SP0.3, Issue #542).
 * - **String identifiers**: queries accept names/IDs as plain strings so that an LLM
 *   can reference network elements without holding Java object references.
 * - **Core-only**: this interface lives in `:core` with no dependency on Koog, Ollama,
 *   or any agent framework. The agent framework wiring happens in `:dispatcher-agent`
 *   (SP1 Issues #546–#551).
 * - **Thread-safety**: the kDisco simulation kernel runs on its own single thread;
 *   callers outside the simulation (e.g. the SP0.10 drive-loop driver) run on their
 *   own threads. [snapshot] is safe to call from any thread — it returns the most
 *   recent snapshot captured on the kDisco thread (off-thread, possibly stale, and
 *   it never blocks the kernel). [captureSnapshot] and the individual `allXxx()` /
 *   single-query methods read live simulation state and must be called on the
 *   kDisco thread.
 *
 * @see DefaultNetworkPerceptionPort
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 */
interface NetworkPerceptionPort {
	// ── Signal aspect ─────────────────────────────────────────────────────

	/**
	 * Read the current signal aspect of the named semaphore.
	 *
	 * @param semaphoreName Name of the semaphore as configured in the railway XML
	 *   (e.g. `"zA"`, `"doB1"`).
	 * @return [SemaphoreReading] with the current [Signal], or `null` if no semaphore
	 *   with that name exists in the current network.
	 */
	fun signalAspect(semaphoreName: String): SemaphoreReading?

	/**
	 * Read the signal aspects of **all** semaphores in the network.
	 *
	 * @return Snapshot list of every semaphore's name and current signal.
	 *   Ordering is deterministic within a single simulation run but may vary
	 *   between networks.
	 */
	fun allSignalAspects(): List<SemaphoreReading>

	// ── Block occupancy ───────────────────────────────────────────────────

	/**
	 * Read the occupancy state of the named track block.
	 *
	 * @param blockId Block name as assigned in the XML (e.g. `"k1"`, `"kA"`) or the
	 *   generated `"sep1-sep2"` label for unnamed blocks.
	 * @return [BlockOccupancyReading] with the current state and owning train ID,
	 *   or `null` if no block with that identifier exists.
	 */
	fun blockOccupancy(blockId: String): BlockOccupancyReading?

	/**
	 * Read the occupancy state of **all** track blocks in the network.
	 *
	 * @return Snapshot list of every block's identifier, state, and owning train ID.
	 */
	fun allBlockOccupancies(): List<BlockOccupancyReading>

	// ── Train position ────────────────────────────────────────────────────

	/**
	 * Read the current kinematics of the named train.
	 *
	 * Only trains that are currently **active** (approved and running in the simulation)
	 * are visible; trains in the unapproved queue are not.
	 *
	 * @param trainId Train identifier (e.g. `"Train #1"`).
	 * @return [TrainPositionReading] with velocity, acceleration, total distance, and
	 *   current front section, or `null` if no active train with that ID is found.
	 */
	fun trainPosition(trainId: String): TrainPositionReading?

	/**
	 * Read the kinematics of **all** currently active trains.
	 *
	 * @return Snapshot list of every active train's position and motion state.
	 */
	fun allTrainPositions(): List<TrainPositionReading>

	// ── Train timetable ───────────────────────────────────────────────────

	/**
	 * Read the timetable of the named train.
	 *
	 * Only trains that are currently **active** (approved and running in the simulation)
	 * are visible; trains in the unapproved queue are not.
	 *
	 * @param trainId Train identifier (e.g. `"Train #1"`).
	 * @return [TimetableReading] with origin, destination, and scheduled times,
	 *   or `null` if no active train with that ID is found.
	 */
	fun trainTimetable(trainId: String): TimetableReading?

	/**
	 * Read the timetables of **all** currently active trains.
	 *
	 * @return Snapshot list of every active train's timetable.
	 */
	fun allTrainTimetables(): List<TimetableReading>

	// ── Per-train first-person perception (SP2a.1) ────────────────────────

	/**
	 * Read the first-person perception snapshot of the named train.
	 *
	 * Returns a bundled [TrainPerceptionReading] combining all five perception
	 * facets that a reactive train agent (SP2a, Issue #537) needs:
	 * signal ahead, speed limit, own kinematics, next timetable event, and
	 * dwell state.
	 *
	 * Only trains that are currently **active** (approved and running in the simulation)
	 * are visible; trains in the unapproved queue are not.
	 *
	 * @param trainId Train identifier (e.g. `"Train #1"`).
	 * @return [TrainPerceptionReading] for the named train, or `null` if no active train
	 *   with that ID is found.
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	fun trainPerception(trainId: String): TrainPerceptionReading?

	/**
	 * Read the first-person perception snapshot of **all** currently active trains.
	 *
	 * @return Snapshot list of every active train's perception reading.
	 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
	 */
	fun allTrainPerceptions(): List<TrainPerceptionReading>

	// ── Full snapshot ─────────────────────────────────────────────────────

	/**
	 * Return the most recent snapshot captured on the kDisco thread, safe to call from
	 * any thread including the external driver thread (SP0.10).
	 *
	 * This is the **off-thread-safe** sense call: it does **not** read live simulation
	 * state — it returns the snapshot most recently published by [captureSnapshot],
	 * so it never blocks or interrupts the kDisco kernel and never throws. The
	 * returned value is a frozen, immutable [SimulationSnapshot] but may be stale
	 * relative to the current simulation time; before the first [captureSnapshot] an
	 * empty snapshot (simulation time at the start baseline, empty lists) is returned.
	 * Callers that hold it across `hold()` boundaries must treat it as potentially
	 * stale.
	 *
	 * For a fresh, consistent, on-thread snapshot, call [captureSnapshot] (kDisco
	 * thread only).
	 *
	 * @return An immutable [SimulationSnapshot] containing all observable state.
	 *
	 * @since Issue #543 (SP0.4 — Goal 10 observable simulation state); made
	 *   off-thread-safe (cached) in Issue #732 (SP0.10 — Goal 10)
	 */
	fun snapshot(): SimulationSnapshot

	/**
	 * Capture a fresh, consistent [SimulationSnapshot] by reading live network state,
	 * and publish it as the latest snapshot returned by [snapshot].
	 *
	 * Calls all `allXxx()` bulk queries in sequence and bundles the results together
	 * with the current simulation time into a single immutable value, then publishes
	 * it so an off-thread [snapshot] caller (e.g. the SP0.10 drive-loop driver) sees
	 * a consistent picture without reading live state itself. Use this method when you
	 * need a **consistent, frozen picture** of the entire network at one moment — for
	 * example, to log the full state for debugging, or to compare two tick states.
	 *
	 * Must be called on the kDisco simulation thread (it reads mutable simulation state
	 * that is not synchronised for off-thread access). Returns the same freshly-captured
	 * snapshot it published, so on-thread callers (e.g. `ShuntingLoop`) get a
	 * consistent, frozen picture identical to the previous single-threaded `snapshot`
	 * behaviour.
	 *
	 * For one-off queries (e.g. "is block k1 free right now?") prefer the individual
	 * methods on this interface.
	 *
	 * @return An immutable [SimulationSnapshot] capturing the network state at the
	 *   current simulation time.
	 *
	 * @since Issue #732 (SP0.10 — Goal 10 off-thread-safe perception)
	 */
	fun captureSnapshot(): SimulationSnapshot
}
