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
 * The pure query of [PathReservationRegistry.occupyingHoldings] (Issue #1220, ruling D3): for every
 * train that stands on at least one block it holds, the blocks it stands on and the RESERVED blocks
 * nobody stands on. It judges nothing -- the stall rule and its clock belong to
 * [DefaultReservationExpiry].
 *
 * Runs on a fresh registry over the vyhybna blocks: no kDisco run, nothing but the registry and the
 * blocks is touched.
 */
class PathReservationRegistryOccupyingHoldingsTest : PathReservationServiceTestBase() {
	private lateinit var freshRegistry: PathReservationRegistry
	private lateinit var kA: DynamicTrackBlock
	private lateinit var zAvA: DynamicTrackBlock
	private lateinit var kB: DynamicTrackBlock

	@BeforeEach
	fun setUpFreshRegistry() {
		freshRegistry = PathReservationRegistry(simulationContext) { 0.0 }
		kA = blockBetween("A", "zA")
		zAvA = blockBetween("zA", "vA")
		kB = blockBetween("B", "zB")
	}

	/** Reserve [block] physically for [trainId] (RESERVED, un-entered) and register it. */
	private fun claim(
		trainId: String,
		block: DynamicTrackBlock
	) {
		block.setUpPath(block.ends().first() as DynamicPathSeparator, trainId)
		freshRegistry.registerAtomic(trainId, listOf(block))
	}

	@Test
	fun `a train standing on its holding is reported with its occupied blocks and its RESERVED tail`() {
		claim("t1", kA)
		claim("t1", zAvA)
		kA.enter(FakeTrackOccupant("t1"))

		assertThat(freshRegistry.occupyingHoldings())
			.containsExactly(OccupyingHolding("t1", occupied = listOf(kA), reserved = listOf(zAvA)))
	}

	@Test
	fun `a train holding only un-entered blocks is not reported`() {
		// That holding is phase 1's (expiredHoldings), never phase 2's.
		claim("t1", kA)
		claim("t1", zAvA)

		assertThat(freshRegistry.occupyingHoldings()).isEmpty()
	}

	@Test
	fun `a train standing on every block it holds is reported with an empty tail`() {
		// The #943 waiter's shape: it has consumed its leg and holds only the blocks it stands on.
		claim("t1", kA)
		kA.enter(FakeTrackOccupant("t1"))

		assertThat(freshRegistry.occupyingHoldings())
			.containsExactly(OccupyingHolding("t1", occupied = listOf(kA), reserved = emptyList()))
	}

	@Test
	fun `a held block that is neither stood on nor RESERVED is in neither list`() {
		claim("t1", kA)
		kA.enter(FakeTrackOccupant("t1"))
		// Registered without a physical reservation: kB stays FREE (the Issue #1025 divergence).
		freshRegistry.registerAtomic("t1", listOf(kB))

		assertThat(freshRegistry.occupyingHoldings())
			.containsExactly(OccupyingHolding("t1", occupied = listOf(kA), reserved = emptyList()))
	}

	@Test
	fun `trains are reported in claim order and the query changes nothing`() {
		claim("t1", kA)
		claim("t2", kB)
		claim("t2", blockBetween("zB", "vB"))
		kA.enter(FakeTrackOccupant("t1"))
		kB.enter(FakeTrackOccupant("t2"))

		val first = freshRegistry.occupyingHoldings()
		assertThat(first.map { it.trainId }).containsExactly("t1", "t2")
		assertThat(freshRegistry.occupyingHoldings()).containsExactly(*first.toTypedArray())
		assertThat(freshRegistry.getBlocks("t2")).containsExactly(kB, blockBetween("zB", "vB"))
	}
}
