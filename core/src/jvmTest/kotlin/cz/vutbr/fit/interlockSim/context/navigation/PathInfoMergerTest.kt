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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry.MergeOutcome
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * [PathInfoMerger]: the PathInfo extension algorithm and its four fail-safe aborts, split out of
 * [PathReservationRegistry] (Issue #1172). The merger is stateless; the registry-level merge
 * contract stays covered by `PathReservationRegistryMergingTest`.
 */
class PathInfoMergerTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var fixture: RegistryUnitFixture
	private lateinit var merger: PathInfoMerger

	@BeforeEach
	fun setUp() {
		fixture =
			RegistryUnitFixture(TestFixtures.loadShuntingSimulationContext(simulationContextFactory).tracked())
		merger = PathInfoMerger(fixture.context)
	}

	@Test
	fun `a continuing route is appended without repeating the shared separator`() {
		val old = fixture.pathInfo(fixture.inOutB, fixture.blockBtoZB, fixture.zB)
		val new = fixture.pathInfo(fixture.zB, fixture.blockZBtoVB, fixture.vB)

		val outcome = merger.merge("t1", old, new)

		assertThat(outcome).isInstanceOf<MergeOutcome.Merged>()
		val merged = outcome.pathInfo
		assertThat(merged.start).isEqualTo(fixture.inOutB)
		assertThat(merged.target).isEqualTo(fixture.vB)
		assertThat(merged.reservedPath.toList())
			.isEqualTo(listOf(fixture.inOutB, fixture.blockBtoZB, fixture.zB, fixture.blockZBtoVB, fixture.vB))
	}

	@Test
	fun `a route starting elsewhere aborts and keeps the stored PathInfo`() {
		val old = fixture.pathInfo(fixture.inOutB, fixture.blockBtoZB, fixture.zB)
		val new = fixture.pathInfo(fixture.vB, fixture.blockVBtoDoB1, fixture.doB1)

		val outcome = merger.merge("t1", old, new)

		assertThat(outcome)
			.isInstanceOf<MergeOutcome.Aborted>()
			.prop(MergeOutcome.Aborted::reason)
			.contains("non-contiguous merge")
		assertThat(outcome.pathInfo).isSameInstanceAs(old)
		assertThat(merger.nonContiguousReason("t1", old, new)).isNotNull()
		assertThat(merger.nonContiguousReason("t1", old, fixture.pathInfo(fixture.zB, fixture.blockZBtoVB, fixture.vB)))
			.isNull()
	}

	@Test
	fun `a route whose start appears twice in it aborts`() {
		val old = fixture.pathInfo(fixture.inOutB, fixture.blockBtoZB, fixture.zB)
		val new = fixture.pathInfo(fixture.zB, fixture.blockZBtoVB, fixture.zB)

		val outcome = merger.merge("t1", old, new)

		assertThat(outcome)
			.isInstanceOf<MergeOutcome.Aborted>()
			.prop(MergeOutcome.Aborted::reason)
			.contains("duplicated new start")
		assertThat(outcome.pathInfo).isSameInstanceAs(old)
	}

	@Test
	fun `a route back over the section just travelled aborts as a reversal`() {
		val old = fixture.pathInfo(fixture.inOutB, fixture.blockBtoZB, fixture.zB)
		val new = fixture.pathInfo(fixture.zB, fixture.blockBtoZB, fixture.inOutB)

		val outcome = merger.merge("t1", old, new)

		assertThat(outcome)
			.isInstanceOf<MergeOutcome.Aborted>()
			.prop(MergeOutcome.Aborted::reason)
			.contains("would reverse it")
		assertThat(outcome.pathInfo).isSameInstanceAs(old)
	}

	@Test
	fun `a third occurrence of a separator aborts as a cycle`() {
		// old already passes zB twice (a legitimate loop); one more pass would be the third. The merger
		// is a pure data operation, so the route need not be drivable -- only not a reversal.
		val old =
			fixture.pathInfo(
				fixture.zB,
				fixture.blockZBtoVB,
				fixture.vB,
				fixture.blockVBtoDoB1,
				fixture.zB,
				fixture.blockBtoZB,
				fixture.inOutB
			)
		val new = fixture.pathInfo(fixture.inOutB, fixture.blockZBtoVB, fixture.zB)

		val outcome = merger.merge("t1", old, new)

		assertThat(outcome)
			.isInstanceOf<MergeOutcome.Aborted>()
			.prop(MergeOutcome.Aborted::reason)
			.contains("would create cycle")
		assertThat(outcome.pathInfo).isSameInstanceAs(old)
	}
}
