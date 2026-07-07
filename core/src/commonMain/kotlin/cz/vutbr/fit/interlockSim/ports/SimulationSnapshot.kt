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

/**
 * Immutable snapshot of all observable simulation state at a single control step.
 *
 * This is the **"sense"** value type for the agent control loop (SP0.4, Issue #543):
 * a dispatcher (or any other agent) calls [NetworkPerceptionPort.snapshot] at the
 * start of a tick to obtain a consistent, frozen picture of the network before
 * deciding what to do.  Because every field is an immutable `data class` or a
 * primitive, the snapshot is safe to pass across thread boundaries and to hold
 * after the tick ends.
 *
 * ## Contents
 *
 * | Field | Source | Agent use |
 * |-------|--------|-----------|
 * | [simTime]       | kDisco `Process.time()` | Timestamp this snapshot was taken |
 * | [semaphores]    | [NetworkPerceptionPort.allSignalAspects] | Current signal aspects for every semaphore |
 * | [blocks]        | [NetworkPerceptionPort.allBlockOccupancies] | Current state of every track block |
 * | [trainPositions]| [NetworkPerceptionPort.allTrainPositions] | Position and velocity of every active train |
 * | [timetables]    | [NetworkPerceptionPort.allTrainTimetables] | Route and schedule of every active train |
 *
 * ## Design note
 *
 * The snapshot is produced by [NetworkPerceptionPort.snapshot] by calling each
 * `allXxx()` bulk query in sequence.  Because kDisco runs on a single dispatcher
 * thread, the snapshot reflects a consistent point in time (no interleaved
 * simulation events between the individual query calls).  Callers outside the
 * kDisco thread — or code that is called after `hold()` resumes — must be aware
 * that the snapshot may be stale by the time it is consumed; it is a record of
 * the instant it was taken, not a live view.
 *
 * ## Relation to live queries
 *
 * For one-off queries (e.g. "is block k1 free right now?") prefer the individual
 * methods on [NetworkPerceptionPort] directly.  Use [snapshot] when you need the
 * complete picture at one moment — for example, to hand it to an LLM dispatcher or
 * to log the full network state for debugging.
 *
 * @property simTime     Simulation time (seconds) at which this snapshot was taken.
 *   `0.0` when called outside an active kDisco simulation.
 * @property semaphores  Signal aspects of every semaphore in the network.  Order is
 *   deterministic within a simulation run; the list is never `null` but may be empty
 *   for a network with no semaphores.
 * @property blocks      Occupancy state of every track block in the network.  Mirrors
 *   [NetworkPerceptionPort.allBlockOccupancies].
 * @property trainPositions Position and motion of every currently active (approved)
 *   train.  Trains in the unapproved queue are absent.
 * @property timetables  Timetable of every currently active (approved) train.
 *
 * @since Issue #543 (SP0.4 — Goal 10 observable simulation state)
 */
data class SimulationSnapshot(
	val simTime: Double,
	val semaphores: List<SemaphoreReading>,
	val blocks: List<BlockOccupancyReading>,
	val trainPositions: List<TrainPositionReading>,
	val timetables: List<TimetableReading>
)
