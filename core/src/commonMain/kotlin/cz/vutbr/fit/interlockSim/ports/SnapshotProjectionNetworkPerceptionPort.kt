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
 * Off-thread-safe [NetworkPerceptionPort] that projects all queries from a cached
 * [SimulationSnapshot] instead of reading live simulation state.
 *
 * ## Purpose — SP1.7 threading contract
 *
 * The `allXxx()` / single-query methods of [DefaultNetworkPerceptionPort] read mutable
 * simulation objects and must therefore run on the kDisco simulation thread.  The Koog
 * agent driver runs on its own thread and calls [cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool.execute]
 * from outside the kDisco kernel — a violation of that contract.
 *
 * This class resolves the violation for **perception tools**: it provides the same
 * [NetworkPerceptionPort] interface but satisfies every query by projecting from the most
 * recently published [SimulationSnapshot] (read via [snapshotProvider]).  Because
 * [SimulationSnapshot] is an immutable value type and [NetworkPerceptionPort.snapshot] is
 * already documented as off-thread-safe, all methods of this class are likewise safe to
 * call from any thread.
 *
 * ## Staleness trade-off
 *
 * The projected snapshot may be one kDisco tick behind the live state (at most ~1 s on
 * the shunting-loop topology).  This is an explicit design decision (documented in the
 * SP1.7 Issue #774 analysis): for LLM dispatcher agents the consistency of a tick-boundary
 * snapshot outweighs the sub-second staleness cost, and the alternative (marshalling every
 * perception call onto the kDisco thread) adds latency and complexity.
 *
 * ## Usage
 *
 * ```kotlin
 * val offThreadPort = SnapshotProjectionNetworkPerceptionPort { livePort.snapshot() }
 * // Pass offThreadPort to perception tools; safe to call from any thread.
 * val tool = SignalAspectTool(offThreadPort)
 * ```
 *
 * @param snapshotProvider Invoked on every query to obtain the current [SimulationSnapshot].
 *   Typically `livePort::snapshot` where `livePort` is the [DefaultNetworkPerceptionPort]
 *   scoped to the simulation context.  Must be thread-safe (the underlying
 *   [NetworkPerceptionPort.snapshot] is `@Volatile`-backed and meets this requirement).
 *
 * @see NetworkPerceptionPort
 * @see DefaultNetworkPerceptionPort
 * @since Issue #774 (SP1.7 — Goal 10 threading contract)
 */
class SnapshotProjectionNetworkPerceptionPort(
	private val snapshotProvider: () -> SimulationSnapshot
) : NetworkPerceptionPort {
	// ── Signal aspects ────────────────────────────────────────────────────

	/**
	 * Delegates to [SimulationSnapshot.signalAspect] — off-thread-safe.
	 */
	override fun signalAspect(semaphoreName: String): SemaphoreReading? = snapshotProvider().signalAspect(semaphoreName)

	/**
	 * Projects from [SimulationSnapshot.semaphores] — off-thread-safe.
	 */
	override fun allSignalAspects(): List<SemaphoreReading> = snapshotProvider().semaphores

	// ── Block occupancies ─────────────────────────────────────────────────

	/**
	 * Delegates to [SimulationSnapshot.blockOccupancy] — off-thread-safe.
	 */
	override fun blockOccupancy(blockId: String): BlockOccupancyReading? = snapshotProvider().blockOccupancy(blockId)

	/**
	 * Projects from [SimulationSnapshot.blocks] — off-thread-safe.
	 */
	override fun allBlockOccupancies(): List<BlockOccupancyReading> = snapshotProvider().blocks

	// ── Train positions ───────────────────────────────────────────────────

	/**
	 * Delegates to [SimulationSnapshot.trainPosition] — off-thread-safe.
	 */
	override fun trainPosition(trainId: String): TrainPositionReading? = snapshotProvider().trainPosition(trainId)

	/**
	 * Projects from [SimulationSnapshot.trainPositions] — off-thread-safe.
	 */
	override fun allTrainPositions(): List<TrainPositionReading> = snapshotProvider().trainPositions

	// ── Train timetables ──────────────────────────────────────────────────

	/**
	 * Delegates to [SimulationSnapshot.trainTimetable] — off-thread-safe.
	 */
	override fun trainTimetable(trainId: String): TimetableReading? = snapshotProvider().trainTimetable(trainId)

	/**
	 * Projects from [SimulationSnapshot.timetables] — off-thread-safe.
	 */
	override fun allTrainTimetables(): List<TimetableReading> = snapshotProvider().timetables

	// ── Train perceptions (SP2a.1) ────────────────────────────────────────

	/**
	 * Delegates to [SimulationSnapshot.trainPerception] — off-thread-safe.
	 *
	 * Note: [SimulationSnapshot.trainPerceptions] defaults to `emptyList()` for snapshots
	 * captured before SP2a.1. This method returns `null` in that case.
	 */
	override fun trainPerception(trainId: String): TrainPerceptionReading? = snapshotProvider().trainPerception(trainId)

	/**
	 * Projects from [SimulationSnapshot.trainPerceptions] — off-thread-safe.
	 */
	override fun allTrainPerceptions(): List<TrainPerceptionReading> = snapshotProvider().trainPerceptions

	// ── Full snapshot ─────────────────────────────────────────────────────

	/**
	 * Returns the snapshot from [snapshotProvider] — off-thread-safe.
	 *
	 * Delegates directly to [snapshotProvider], which is the off-thread-safe
	 * [NetworkPerceptionPort.snapshot] of the live port.
	 */
	override fun snapshot(): SimulationSnapshot = snapshotProvider()

	/**
	 * Not supported by this off-thread projection port.
	 *
	 * [captureSnapshot] reads mutable live simulation state and is valid only on
	 * the kDisco simulation thread.  Calling it on a
	 * [SnapshotProjectionNetworkPerceptionPort] — which is specifically intended for
	 * off-thread use — is a programming error; callers should use the live
	 * [DefaultNetworkPerceptionPort.captureSnapshot] from the kDisco thread and then
	 * pass the cached snapshot to [snapshotProvider].
	 *
	 * @throws UnsupportedOperationException always.
	 */
	override fun captureSnapshot(): SimulationSnapshot =
		throw UnsupportedOperationException(
			"SnapshotProjectionNetworkPerceptionPort is off-thread-only; " +
				"call captureSnapshot() on the live DefaultNetworkPerceptionPort from the kDisco thread instead."
		)
}
