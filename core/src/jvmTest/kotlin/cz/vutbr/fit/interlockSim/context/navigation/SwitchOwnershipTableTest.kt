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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * [SwitchOwnershipTable]: the train <-> switch claims, their flank purpose and the stale-claim
 * rule, split out of [PathReservationRegistry] (Issue #1172). The table reads the blocks a train
 * holds through a function; here a plain map stands in for the block table.
 */
class SwitchOwnershipTableTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var fixture: RegistryUnitFixture
	private val held = mutableMapOf<String, MutableList<DynamicTrackBlock>>()
	private lateinit var table: SwitchOwnershipTable

	@BeforeEach
	fun setUp() {
		fixture =
			RegistryUnitFixture(TestFixtures.loadShuntingSimulationContext(simulationContextFactory).tracked())
		held.clear()
		table = SwitchOwnershipTable { trainId -> held[trainId] }
	}

	@Test
	fun `registerSwitches locks the switch under a plain claim`() {
		table.registerSwitches("t1", listOf(fixture.vB))

		assertThat(fixture.vB.locked).isTrue()
		assertThat(table.getSwitchClaim(fixture.vB)).isEqualTo(SwitchClaim("t1", isFlank = false))
		assertThat(table.getSwitchOwner(fixture.vB)).isEqualTo("t1")
		assertThat(table.getSwitches("t1")).containsExactly(fixture.vB)
		assertThat(table.isOwnedByOtherTrain(fixture.vB, "t2")).isTrue()
		assertThat(table.isOwnedByOtherTrain(fixture.vB, "t1")).isFalse()
	}

	@Test
	fun `a foreign registration throws and changes nothing`() {
		table.registerSwitches("t1", listOf(fixture.vB))

		assertFailure { table.registerSwitches("t2", listOf(fixture.vB)) }
			.isInstanceOf(IllegalStateException::class)

		assertThat(table.getSwitchOwner(fixture.vB)).isEqualTo("t1")
		assertThat(table.getSwitches("t2")).isEmpty()
	}

	@Test
	fun `a flank claim stays a flank claim over a plain re-registration and is never stale`() {
		table.registerFlankSwitches("t1", listOf(fixture.vB))
		table.registerSwitches("t1", listOf(fixture.vB))

		assertThat(table.isFlankProtected(fixture.vB)).isTrue()
		assertThat(table.isStaleSwitchOwnership(fixture.vB)).isFalse()
	}

	@Test
	fun `a plain claim is stale exactly when its owner holds no block bounded by the switch`() {
		table.registerSwitches("t1", listOf(fixture.vB))
		assertThat(table.isStaleSwitchOwnership(fixture.vB), "no held blocks at all").isTrue()

		held["t1"] = mutableListOf(fixture.blockBtoZB)
		assertThat(table.isStaleSwitchOwnership(fixture.vB), "held block away from the switch").isTrue()

		held["t1"] = mutableListOf(fixture.blockZBtoVB)
		assertThat(table.isStaleSwitchOwnership(fixture.vB), "held block at the switch").isFalse()
	}

	@Test
	fun `reclaimStaleSwitchOwnership releases only a stale claim`() {
		held["t1"] = mutableListOf(fixture.blockVBtoDoB1)
		table.registerSwitches("t1", listOf(fixture.vB))
		assertThat(table.reclaimStaleSwitchOwnership(fixture.vB), "live claim").isNull()

		held.remove("t1")
		assertThat(table.reclaimStaleSwitchOwnership(fixture.vB), "stale claim").isEqualTo("t1")

		assertThat(table.getSwitchClaim(fixture.vB)).isNull()
		assertThat(fixture.vB.locked).isFalse()
		assertThat(table.getSwitches("t1")).isEmpty()
	}

	@Test
	fun `unregisterSwitch ignores a non-owner and unregisterSwitches releases every claim`() {
		table.registerSwitches("t1", listOf(fixture.vB))

		assertThat(table.unregisterSwitch("t2", fixture.vB)).isFalse()
		assertThat(fixture.vB.locked).isTrue()

		assertThat(table.unregisterSwitches("t1")).containsExactly(fixture.vB)
		assertThat(table.unregisterSwitches("t1")).isEmpty()
		assertThat(fixture.vB.locked).isFalse()
		assertThat(table.getSwitchOwner(fixture.vB)).isNull()
	}

	@Test
	fun `clear drops every claim with its purpose`() {
		table.registerFlankSwitches("t1", listOf(fixture.vB))

		table.clear()

		assertThat(table.getSwitchClaim(fixture.vB)).isNull()
		assertThat(table.isFlankProtected(fixture.vB)).isFalse()
		assertThat(table.getSwitches("t1")).isEmpty()
	}
}
