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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * [BlockOwnershipTable]: the train <-> block ownership maps and their registration timestamps,
 * split out of [PathReservationRegistry] (Issue #1172). Pins the behaviour the registry had before
 * the split; the registry-level contract stays covered by `PathReservationRegistryTest`.
 */
class BlockOwnershipTableTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var fixture: RegistryUnitFixture
	private var now = 0.0
	private lateinit var table: BlockOwnershipTable

	@BeforeEach
	fun setUp() {
		fixture =
			RegistryUnitFixture(TestFixtures.loadShuntingSimulationContext(simulationContextFactory).tracked())
		now = 0.0
		table = BlockOwnershipTable { now }
	}

	@Test
	fun `registerAtomic records owner, held blocks and the registration time`() {
		now = 4.0
		val result = table.registerAtomic("t1", listOf(fixture.blockBtoZB, fixture.blockZBtoVB))

		assertThat(result).isEqualTo(PathReservationRegistry.RegistrationResult.Success)
		assertThat(table.getOwner(fixture.blockBtoZB)).isEqualTo("t1")
		assertThat(table.getBlocks("t1")).containsExactly(fixture.blockBtoZB, fixture.blockZBtoVB)
		assertThat(table.getRegisteredAtSimTime(fixture.blockZBtoVB)).isEqualTo(4.0)
		assertThat(table.isRegistered(fixture.blockBtoZB)).isTrue()
		assertThat(table.trainCount()).isEqualTo(1)
		assertThat(table.blockCount()).isEqualTo(2)
	}

	@Test
	fun `a conflict registers none of the requested blocks`() {
		table.registerAtomic("t1", listOf(fixture.blockZBtoVB))

		val result = table.registerAtomic("t2", listOf(fixture.blockBtoZB, fixture.blockZBtoVB))

		assertThat(result).isEqualTo(
			PathReservationRegistry.RegistrationResult.Conflict(
				fixture.blockZBtoVB,
				"t1",
				PathReservationRegistry.ConflictReason.RESERVED_BY_OTHER_TRAIN
			)
		)
		assertThat(table.getOwner(fixture.blockBtoZB)).isNull()
		assertThat(table.heldBlocksOrNull("t2")).isNull()
	}

	@Test
	fun `a block occupied by another train is an occupied conflict even without a registry owner`() {
		val block = fixture.blockBtoZB
		block.setUpPath(fixture.inOutB, "t1")
		block.enter(FakeTrackOccupant("t1"))

		val result = table.registerAtomic("t2", listOf(block))

		assertThat(result).isEqualTo(
			PathReservationRegistry.RegistrationResult.Conflict(
				block,
				"t1",
				PathReservationRegistry.ConflictReason.OCCUPIED_BY_OTHER_TRAIN
			)
		)
		assertThat(table.isBlockAvailable(block)).isFalse()
	}

	@Test
	fun `release refuses a foreign or still reserved block and drops the train entry with its last block`() {
		table.registerAtomic("t1", listOf(fixture.blockBtoZB, fixture.blockZBtoVB))
		fixture.blockZBtoVB.setUpPath(fixture.zB, "t1")

		assertThat(table.release("t2", fixture.blockBtoZB), "foreign owner").isFalse()
		assertThat(table.release("t1", fixture.blockZBtoVB), "still reserved").isFalse()
		assertThat(table.release("t1", fixture.blockBtoZB), "free and owned").isTrue()

		assertThat(table.getOwner(fixture.blockBtoZB)).isNull()
		assertThat(table.getRegisteredAtSimTime(fixture.blockBtoZB)).isNull()
		assertThat(table.getBlocks("t1")).containsExactly(fixture.blockZBtoVB)

		fixture.blockZBtoVB.cancelPathSetup(fixture.zB)
		assertThat(table.release("t1", fixture.blockZBtoVB)).isTrue()
		assertThat(table.heldBlocksOrNull("t1"), "train entry").isNull()
		assertThat(table.isBlockAvailable(fixture.blockZBtoVB)).isTrue()
	}

	@Test
	fun `removeTrain drops every block of the train and answers null for a train without an entry`() {
		table.registerAtomic("t1", listOf(fixture.blockBtoZB, fixture.blockZBtoVB))
		table.registerAtomic("t2", listOf(fixture.blockVBtoDoB1))

		assertThat(table.removeTrain("t1")).isEqualTo(listOf(fixture.blockBtoZB, fixture.blockZBtoVB))
		assertThat(table.removeTrain("t1")).isNull()

		assertThat(table.getOwner(fixture.blockBtoZB)).isNull()
		assertThat(table.getRegisteredAtSimTime(fixture.blockZBtoVB)).isNull()
		assertThat(table.getOwner(fixture.blockVBtoDoB1)).isEqualTo("t2")
	}

	@Test
	fun `getOccupiedBlocks lists only the held blocks the train physically occupies`() {
		table.registerAtomic("t1", listOf(fixture.blockBtoZB, fixture.blockZBtoVB))
		fixture.blockBtoZB.setUpPath(fixture.inOutB, "t1")
		fixture.blockBtoZB.enter(FakeTrackOccupant("t1"))

		assertThat(table.getOccupiedBlocks("t1")).containsExactly(fixture.blockBtoZB)
		assertThat(table.getOccupiedBlocks("ghost")).isEqualTo(emptyList())
	}

	@Test
	fun `clear drops owners, held blocks and timestamps`() {
		table.registerAtomic("t1", listOf(fixture.blockBtoZB))

		table.clear()

		assertThat(table.getOwner(fixture.blockBtoZB)).isNull()
		assertThat(table.getRegisteredAtSimTime(fixture.blockBtoZB)).isNull()
		assertThat(table.trainCount()).isEqualTo(0)
		assertThat(table.blockCount()).isEqualTo(0)
	}
}
