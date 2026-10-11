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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.MergeOutcome
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * [PathInfoStore]: the per-train stored route (PathInfo) and its trim bookkeeping, split out of
 * [PathReservationRegistry] (Issue #1172). The store reads the blocks a train holds through a
 * function; here a plain map stands in for the block table.
 */
class PathInfoStoreTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var fixture: RegistryUnitFixture
	private val held = mutableMapOf<String, List<DynamicTrackBlock>>()
	private lateinit var store: PathInfoStore

	@BeforeEach
	fun setUp() {
		fixture =
			RegistryUnitFixture(TestFixtures.loadShuntingSimulationContext(simulationContextFactory).tracked())
		held.clear()
		store = PathInfoStore(fixture.context) { trainId -> held[trainId] ?: emptyList() }
	}

	@Test
	fun `the first registration stores the PathInfo itself and a continuing one merges`() {
		val first = fixture.pathInfo(fixture.inOutB, fixture.blockBtoZB, fixture.zB)

		assertThat(store.register("t1", first)).isEqualTo(MergeOutcome.Merged(first))
		assertThat(store.get("t1")).isSameInstanceAs(first)

		val outcome = store.register("t1", fixture.pathInfo(fixture.zB, fixture.blockZBtoVB, fixture.vB))

		assertThat(outcome).isInstanceOf<MergeOutcome.Merged>()
		assertThat(store.get("t1")).isSameInstanceAs(outcome.pathInfo)
		assertThat(store.get("t1")!!.target).isEqualTo(fixture.vB)
	}

	@Test
	fun `restore replaces or removes the entry and remove drops it`() {
		val snapshot = fixture.pathInfo(fixture.inOutB, fixture.blockBtoZB, fixture.zB)
		store.register("t1", fixture.approachPathInfo())

		store.restore("t1", snapshot)
		assertThat(store.get("t1")).isSameInstanceAs(snapshot)

		store.restore("t1", null)
		assertThat(store.get("t1")).isNull()

		store.register("t1", snapshot)
		store.remove("t1")
		assertThat(store.get("t1")).isNull()
	}

	@Test
	fun `isPathExtendedBeyond is true only for a separator with track after it`() {
		store.register("t1", fixture.approachPathInfo())

		assertThat(store.isPathExtendedBeyond("t1", fixture.zB)).isTrue()
		assertThat(store.isPathExtendedBeyond("t1", fixture.doB1)).isFalse()
		assertThat(store.isPathExtendedBeyond("ghost", fixture.zB)).isFalse()
	}

	@Test
	fun `nonContinuingCandidateReason accepts a first route and a continuing one only`() {
		val candidate = fixture.pathInfo(fixture.zB, fixture.blockZBtoVB, fixture.vB)
		assertThat(store.nonContinuingCandidateReason("t1", candidate), "no stored route").isNull()

		store.register("t1", fixture.pathInfo(fixture.inOutB, fixture.blockBtoZB, fixture.zB))

		assertThat(store.nonContinuingCandidateReason("t1", candidate)).isNull()
		assertThat(store.nonContinuingCandidateReason("t1", fixture.approachPathInfo())).isNotNull()
	}

	@Test
	fun `pathInfoEndAfter names the separator after the last kept block and the block beyond it`() {
		store.register("t1", fixture.approachPathInfo())

		val end = store.pathInfoEndAfter("t1", listOf(fixture.blockBtoZB))

		assertThat(end).isEqualTo(PathReservationRegistry.PathInfoEnd(fixture.zB, fixture.blockZBtoVB))
		assertThat(store.pathInfoEndAfter("t1", listOf(fixture.blockVBtoDoB1)))
			.isEqualTo(PathReservationRegistry.PathInfoEnd(fixture.doB1, null))
		assertThat(store.pathInfoEndAfter("t1", emptyList())).isNull()
	}

	@Test
	fun `a switch is never a valid end`() {
		assertThat(store.isValidPathInfoEnd(fixture.vB, fixture.blockVBtoDoB1)).isFalse()
		assertThat(store.isValidPathInfoEnd(fixture.vB)).isFalse()
		assertThat(store.isValidPathInfoEnd(fixture.inOutB)).isTrue()
	}

	@Test
	fun `trimPathInfoToHeldBlocks cuts the route back to the separator after the held blocks`() {
		store.register("t1", fixture.approachPathInfo())
		held["t1"] = listOf(fixture.blockBtoZB)

		assertThat(store.trimPathInfoToHeldBlocks("t1")).isTrue()

		val trimmed = store.get("t1")!!
		assertThat(trimmed.start).isEqualTo(fixture.inOutB)
		assertThat(trimmed.target).isEqualTo(fixture.zB)
		assertThat(trimmed.reservedPath.toList()).isEqualTo(listOf(fixture.inOutB, fixture.blockBtoZB, fixture.zB))
	}

	@Test
	fun `a trim that would end the route at a switch is refused and keeps the PathInfo`() {
		val stored = fixture.approachPathInfo()
		store.register("t1", stored)
		held["t1"] = listOf(fixture.blockBtoZB, fixture.blockZBtoVB)

		assertThat(store.canTrimPathInfoTo("t1", held.getValue("t1"))).isFalse()
		assertThat(store.trimPathInfoTo("t1", fixture.vB)).isFalse()
		assertThat(store.trimPathInfoToHeldBlocks("t1")).isFalse()
		assertThat(store.get("t1")).isSameInstanceAs(stored)
	}

	@Test
	fun `canTrimPathInfoTo needs no trim without a PathInfo or with the whole route kept`() {
		assertThat(store.canTrimPathInfoTo("ghost", emptyList())).isTrue()

		store.register("t1", fixture.approachPathInfo())

		assertThat(store.canTrimPathInfoTo("t1", listOf(fixture.blockVBtoDoB1))).isTrue()
		assertThat(store.canTrimPathInfoTo("t1", emptyList())).isFalse()
	}

	@Test
	fun `clear drops every stored PathInfo`() {
		store.register("t1", fixture.approachPathInfo())

		store.clear()

		assertThat(store.get("t1")).isNull()
	}
}
