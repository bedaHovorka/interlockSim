/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for [DefaultMetricsCollectionService] KPI derivation.
 *
 * These drive [DefaultMetricsCollectionService.handleBlockEvent] directly with synthetic
 * [BlockEvent]s (no simulation context required), mirroring the
 * [cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService] precedent.
 *
 * Tests run in `commonTest` and execute on both JVM and native targets.
 *
 * @since Issue #672 (Goal 6 SP1)
 */
class DefaultMetricsCollectionServiceTest : KoinComponent {
	// ── Helpers ───────────────────────────────────────────────────────────────

	/** Minimal [TrackOccupant] carrying only a name, for synthetic [BlockEvent]s. */
	private class NamedOccupant(
		override val name: String
	) : TrackOccupant {
		override fun distanceToSemaphore(): Double = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}

	/** Context reference kept to ensure the scope stays alive during the test. */
	private var context: DefaultSimulationContext? = null

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDown() {
		context?.close()
		context = null
		stopKoin()
	}

	/** Get a real [DynamicTrackBlock] from a minimal transformed context. */
	private fun realBlock(): DynamicTrackBlock {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		context = ctx
		return ctx.getGraph().values().first()
	}

	/**
	 * Get a real [DefaultSimulationContext] built from [NetworkResources.LINEAR_TRACK_XML].
	 *
	 * The linear-track fixture has exactly one track block, so `getGraph().size() == 1`.
	 * The context is kept in [context] so the Koin scope stays alive for the test.
	 */
	private fun realContext(): DefaultSimulationContext {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		context = ctx
		return ctx
	}

	// ── Empty / initial snapshot ──────────────────────────────────────────────

	@Test
	fun `initial snapshot has all-zero KPIs`() {
		val service = DefaultMetricsCollectionService()
		val snap = service.getSnapshot()

		assertThat(snap.conflictCount).isZero()
		assertThat(snap.completedTrains).isZero()
		assertThat(snap.throughput).isZero()
		assertThat(snap.totalWaitSeconds).isZero()
		assertThat(snap.averageWaitSeconds).isZero()
		assertThat(snap.occupiedBlocks).isZero()
		assertThat(snap.totalBlocks).isZero()
		assertThat(snap.utilization).isZero()
		assertThat(snap.time).isZero()
	}

	// ── Conflict counting ─────────────────────────────────────────────────────

	@Test
	fun `ReservationConflictDetected increments conflictCount`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		service.handleBlockEvent(
			BlockEvent.ReservationConflictDetected(block, "T1", "T2", time = 5.0)
		)
		assertThat(service.getSnapshot().conflictCount).isEqualTo(1)

		service.handleBlockEvent(
			BlockEvent.ReservationConflictDetected(block, "T1", "T3", time = 10.0)
		)
		assertThat(service.getSnapshot().conflictCount).isEqualTo(2)
	}

	// ── Train completion (throughput) ─────────────────────────────────────────

	@Test
	fun `BlockReleased after BlockReserved increments completedTrains when reservation count reaches zero`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		assertThat(service.getSnapshot().completedTrains).isZero()

		service.handleBlockEvent(BlockEvent.BlockReleased(block, "T1", time = 20.0))
		assertThat(service.getSnapshot().completedTrains).isEqualTo(1)
	}

	@Test
	fun `train with multiple reserved blocks only counted as completed when all blocks released`() {
		val service = DefaultMetricsCollectionService()
		val blockA = realBlock()
		val blockB = realBlock()

		service.handleBlockEvent(BlockEvent.BlockReserved(blockA, "T1", time = 1.0))
		service.handleBlockEvent(BlockEvent.BlockReserved(blockB, "T1", time = 2.0))
		service.handleBlockEvent(BlockEvent.BlockReleased(blockA, "T1", time = 10.0))

		// Only one of two blocks released — should not be counted as complete yet
		assertThat(service.getSnapshot().completedTrains).isZero()

		service.handleBlockEvent(BlockEvent.BlockReleased(blockB, "T1", time = 20.0))
		assertThat(service.getSnapshot().completedTrains).isEqualTo(1)
	}

	@Test
	fun `throughput is completedTrains divided by simulation time`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		service.handleBlockEvent(BlockEvent.BlockReleased(block, "T1", time = 100.0))

		val snap = service.getSnapshot()
		assertThat(snap.completedTrains).isEqualTo(1)
		// throughput = 1 / 100.0 = 0.01
		assertThat(snap.throughput).isCloseTo(0.01, delta = 1e-9)
	}

	// ── Delay (wait time) ─────────────────────────────────────────────────────

	@Test
	fun `wait time is computed from BlockReserved to OccupancySet for the same block`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		// Reserved at t=2, occupied at t=7 → wait = 5 s
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 2.0))
		service.handleBlockEvent(BlockEvent.OccupancySet(block, NamedOccupant("T1"), time = 7.0))

		val snap = service.getSnapshot()
		assertThat(snap.totalWaitSeconds).isCloseTo(5.0, delta = 1e-9)
		assertThat(snap.averageWaitSeconds).isCloseTo(5.0, delta = 1e-9)
	}

	@Test
	fun `wait time accumulates across multiple blocks`() {
		val service = DefaultMetricsCollectionService()
		val blockA = realBlock()
		val blockB = realBlock()

		service.handleBlockEvent(BlockEvent.BlockReserved(blockA, "T1", time = 0.0))
		service.handleBlockEvent(BlockEvent.OccupancySet(blockA, NamedOccupant("T1"), time = 3.0))

		service.handleBlockEvent(BlockEvent.BlockReserved(blockB, "T2", time = 1.0))
		service.handleBlockEvent(BlockEvent.OccupancySet(blockB, NamedOccupant("T2"), time = 5.0))

		val snap = service.getSnapshot()
		// waitA = 3, waitB = 4 → total = 7
		assertThat(snap.totalWaitSeconds).isCloseTo(7.0, delta = 1e-9)
		// average = 7 / 2 = 3.5
		assertThat(snap.averageWaitSeconds).isCloseTo(3.5, delta = 1e-9)
	}

	// ── Block utilization ─────────────────────────────────────────────────────

	@Test
	fun `OccupancySet increments occupiedBlocks and OccupancyCleared decrements it`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		service.handleBlockEvent(BlockEvent.OccupancySet(block, NamedOccupant("T1"), time = 1.0))
		assertThat(service.getSnapshot().occupiedBlocks).isEqualTo(1)

		service.handleBlockEvent(BlockEvent.OccupancyCleared(block, time = 5.0))
		assertThat(service.getSnapshot().occupiedBlocks).isZero()
	}

	@Test
	fun `utilization and totalBlocks reflect the env graph after OccupancySet`() {
		// Construct the service against a real context so totalBlocks is derived
		// from env.getGraph().size() rather than defaulting to 0 (env = null).
		val ctx = realContext()
		val block = ctx.getGraph().values().first()
		val service = DefaultMetricsCollectionService(env = ctx)

		// LINEAR_TRACK_XML has exactly one track block.
		assertThat(service.getSnapshot().totalBlocks).isEqualTo(1)

		service.handleBlockEvent(BlockEvent.OccupancySet(block, NamedOccupant("T1"), time = 1.0))

		val snap = service.getSnapshot()
		assertThat(snap.occupiedBlocks).isEqualTo(1)
		assertThat(snap.totalBlocks).isEqualTo(1)
		// utilization = occupiedBlocks / totalBlocks = 1 / 1 = 1.0
		assertThat(snap.utilization).isCloseTo(1.0, delta = 1e-9)
	}

	// ── Metrics services facade (ISP accessor) ───────────────────────────────

	@Test
	fun `getMetricsServices exposes a working service via the scoped Koin binding`() {
		val ctx = realContext()

		// The accessor is the API surface SP2–SP4 will build on. Resolving it
		// pre-run() must initialise the lazy field and construct the scoped
		// DefaultMetricsCollectionService(env = ctx).
		val service = ctx.getMetricsServices().getMetricsCollectionService()

		// The service reflects the real graph size (1 for LINEAR_TRACK_XML),
		// proving the scoped binding wired the context as env.
		assertThat(service.getSnapshot().totalBlocks).isEqualTo(ctx.getGraph().size())
		assertThat(service.getSnapshot().totalBlocks).isEqualTo(1)
	}

	// ── Snapshot listener ─────────────────────────────────────────────────────

	@Test
	fun `onSnapshot listener is called after each event`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()
		val received = mutableListOf<MetricsSnapshot>()

		service.onSnapshot { received.add(it) }

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		service.handleBlockEvent(BlockEvent.BlockReleased(block, "T1", time = 5.0))

		assertThat(received.size).isEqualTo(2)
		// After release, train is complete
		assertThat(received.last().completedTrains).isEqualTo(1)
	}

	@Test
	fun `throwing snapshot listener does not prevent remaining listeners from being called`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()
		val reached = mutableListOf<Int>()

		service.onSnapshot { throw IllegalStateException("boom") }
		service.onSnapshot { reached.add(1) }

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))

		assertThat(reached.size).isEqualTo(1)
	}

	@Test
	fun `removeSnapshotListener is a no-op for a never-registered reference`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()
		val received = mutableListOf<MetricsSnapshot>()

		service.onSnapshot { received.add(it) }

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		assertThat(received.size).isEqualTo(1)

		// Removing a never-registered reference must be a no-op (no throw,
		// registered listener still fires).
		service.removeSnapshotListener { /* never registered */ }
		service.handleBlockEvent(BlockEvent.BlockReleased(block, "T1", time = 5.0))
		assertThat(received.size).isEqualTo(2)
	}

	@Test
	fun `removeSnapshotListener stops the listener from receiving further snapshots`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()
		val received = mutableListOf<MetricsSnapshot>()
		val listener: (MetricsSnapshot) -> Unit = { received.add(it) }

		service.onSnapshot(listener)
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
		assertThat(received.size).isEqualTo(1)

		// Identity-based removal: the same reference must be passed back.
		service.removeSnapshotListener(listener)
		service.handleBlockEvent(BlockEvent.BlockReleased(block, "T1", time = 5.0))
		// No new invocation after removal.
		assertThat(received.size).isEqualTo(1)
	}

	// ── Synthetic event → MetricsSnapshot round-trip ─────────────────────────

	@Test
	fun `synthetic block events produce the expected MetricsSnapshot`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		// Simulate: train T1 reserves, occupies, and releases one block (conflict-free)
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 0.0))
		service.handleBlockEvent(BlockEvent.OccupancySet(block, NamedOccupant("T1"), time = 4.0))
		service.handleBlockEvent(BlockEvent.OccupancyCleared(block, time = 8.0))
		service.handleBlockEvent(BlockEvent.BlockReleased(block, "T1", time = 9.0))

		val snap = service.getSnapshot()

		assertThat(snap.conflictCount).isEqualTo(0)
		assertThat(snap.completedTrains).isEqualTo(1)
		// throughput = 1 / 9.0
		assertThat(snap.throughput).isGreaterThan(0.0)
		// wait = 4 - 0 = 4 s
		assertThat(snap.totalWaitSeconds).isCloseTo(4.0, delta = 1e-9)
		assertThat(snap.averageWaitSeconds).isCloseTo(4.0, delta = 1e-9)
		// Block was cleared before snapshot
		assertThat(snap.occupiedBlocks).isZero()
		// Time matches last event time
		assertThat(snap.time).isCloseTo(9.0, delta = 1e-9)
	}
}
