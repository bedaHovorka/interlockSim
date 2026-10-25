/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The pure stale-holding query of [PathReservationRegistry.expiredHoldings] (Issue #1174, absorbs
 * #975): which trains hold only un-entered RESERVED blocks and have made no new block registration
 * for at least the TTL, in simulation time read from an injected clock.
 *
 * Runs on a fresh registry over the vyhybna blocks, so the clock is the test's own: no kDisco run,
 * and nothing but the registry is touched.
 */
class PathReservationRegistryExpiredHoldingsTest : PathReservationServiceTestBase() {
	private var clock = 0.0
	private lateinit var timedRegistry: PathReservationRegistry
	private lateinit var kA: DynamicTrackBlock
	private lateinit var kB: DynamicTrackBlock

	@BeforeEach
	fun setUpTimedRegistry() {
		timedRegistry = PathReservationRegistry(simulationContext) { clock }
		kA = blockBetween("A", "zA")
		kB = blockBetween("B", "zB")
	}

	/** Reserve [block] physically for [trainId] (RESERVED, un-entered) and register it at [at]. */
	private fun claim(
		trainId: String,
		block: DynamicTrackBlock,
		at: Double
	) {
		if (block.trainName == null) {
			block.setUpPath(block.ends().first() as DynamicPathSeparator, trainId)
		}
		clock = at
		timedRegistry.registerAtomic(trainId, listOf(block))
	}

	@Test
	fun `an un-entered holding is reported once its newest registration is TTL old`() {
		claim("t1", kA, at = 0.0)

		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 59.999)).isEmpty()
		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 60.0)).containsExactly(ExpiredHolding("t1", 60.0))
	}

	@Test
	fun `a new claim restarts the clock`() {
		claim("t1", kA, at = 0.0)
		claim("t1", kB, at = 50.0)

		// kA is 100 s old, but the train's newest claim (kB) is only 50 s old: the holding is live.
		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 100.0)).isEmpty()
		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 110.0)).containsExactly(ExpiredHolding("t1", 60.0))
	}

	@Test
	fun `re-registering a held block does not restart the clock`() {
		claim("t1", kA, at = 0.0)
		// The same owner registering the same block again keeps its first timestamp (Issue #975),
		// so asking for the same route over and over is not progress.
		claim("t1", kA, at = 50.0)

		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 60.0)).containsExactly(ExpiredHolding("t1", 60.0))
	}

	@Test
	fun `a train standing on one block of its holding never expires at any age`() {
		claim("t1", kA, at = 0.0)
		claim("t1", kB, at = 0.0)
		kA.enter(FakeTrackOccupant("t1"))

		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 1.0e6)).isEmpty()
	}

	@Test
	fun `a held block that is not RESERVED keeps the holding out of the query`() {
		// Registered without a physical reservation: the block stays FREE. That is the Issue #1025
		// registry-vs-physical divergence, not an un-entered route -- the kernel expiry leaves it to
		// the per-block release funnel and the dispatcher's sweeper.
		clock = 0.0
		timedRegistry.registerAtomic("t1", listOf(kA))

		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 1.0e6)).isEmpty()
	}

	@Test
	fun `only stale trains are reported, in registration order`() {
		claim("t1", kA, at = 0.0)
		claim("t2", kB, at = 30.0)

		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 60.0)).containsExactly(ExpiredHolding("t1", 60.0))
		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 90.0))
			.containsExactly(ExpiredHolding("t1", 90.0), ExpiredHolding("t2", 60.0))
	}

	@Test
	fun `a released holding drops out of the query`() {
		claim("t1", kA, at = 0.0)
		timedRegistry.unregister("t1")

		assertThat(timedRegistry.expiredHoldings(ttl = TTL, now = 1.0e6)).isEmpty()
	}

	@Test
	fun `the default now is the registry's own clock`() {
		claim("t1", kA, at = 0.0)

		clock = 59.0
		assertThat(timedRegistry.expiredHoldings(ttl = TTL)).isEmpty()
		clock = 61.0
		assertThat(timedRegistry.expiredHoldings(ttl = TTL)).containsExactly(ExpiredHolding("t1", 61.0))
	}

	private companion object {
		const val TTL = DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS
	}
}
