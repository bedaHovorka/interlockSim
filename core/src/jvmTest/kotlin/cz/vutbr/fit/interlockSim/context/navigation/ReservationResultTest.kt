/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Coverage test for PathReservationService.ReservationResult sealed hierarchy (Issue #453).
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.coreTestModule
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.InputStream

/**
 * Smoke tests for [PathReservationService.ReservationResult] subtypes that are
 * instantiable but were not exercised by existing tests (NoPathExists, Conflict).
 */
@DisplayName("PathReservationService.ReservationResult (Issue #453)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationResultTest : KoinTestBase() {
	private lateinit var blocks: List<DynamicTrackBlock>

	@BeforeAll
	fun initFixture() {
		// Build the shared fixture once per class. KoinTestBase runs its own
		// @BeforeEach/@AfterEach start/stop cycle per test, so here we bring up
		// Koin briefly, materialize the fixture data, then tear it down again
		// so the per-test lifecycle remains unchanged.
		val koinApp = startKoin { modules(coreTestModule) }
		try {
			val editingContextFactory = koinApp.koin.get<JvmEditingContextFactory>()
			val simulationContextFactory = koinApp.koin.get<SimulationContextFactory>()
			val xmlStream: InputStream =
				TestFixtures.loadShuntingXml()
					?: throw IllegalStateException("vyhybna.xml not found in resources")
			val editing = editingContextFactory.createContext(xmlStream) as EditingContext
			val simCtx = simulationContextFactory.createContext(editing) as DefaultSimulationContext
			val inOuts = simCtx.getInOuts().toList()
			check(inOuts.size >= 2) { "fixture expected ≥2 InOuts; got ${inOuts.size}" }
			val navigator: TopologyNavigator = simCtx.scope.get()
			val paths = navigator.findAllTopologicalPaths(inOuts[0], inOuts[1])
			check(paths.isNotEmpty()) { "fixture expected ≥1 topological path between inOuts[0] and inOuts[1]" }
			blocks =
				paths
					.first()
					.mapNotNull { section ->
						val block = section.getTrackBlock()
						if (block is DynamicTrackBlock) block else null
					}.distinct()
		} finally {
			stopKoin()
		}
	}

	@Test
	fun `NoPathExists is a singleton data object`() {
		val first: PathReservationService.ReservationResult = PathReservationService.ReservationResult.NoPathExists
		val second: PathReservationService.ReservationResult = PathReservationService.ReservationResult.NoPathExists

		assertThat(first).isSameInstanceAs(second)
		assertThat(first).isInstanceOf<PathReservationService.ReservationResult.NoPathExists>()
	}

	@Test
	fun `Conflict carries conflicting block and existing owner`() {
		val block = blocks.first()
		val conflict = PathReservationService.ReservationResult.Conflict(block, "T1")

		assertThat(conflict.conflictingBlock).isSameInstanceAs(block)
		assertThat(conflict.existingOwner).isEqualTo("T1")
		assertThat(conflict).isInstanceOf<PathReservationService.ReservationResult.Conflict>()
	}

	@Test
	fun `Conflict data class equality and hashCode`() {
		val block = blocks.first()
		val a = PathReservationService.ReservationResult.Conflict(block, "T1")
		val b = PathReservationService.ReservationResult.Conflict(block, "T1")
		val c = PathReservationService.ReservationResult.Conflict(block, "T2")

		assertThat(a).isEqualTo(b)
		assertThat(a.hashCode()).isEqualTo(b.hashCode())
		assertThat(a).isNotEqualTo(c)
		// Exercise copy() + componentN of data class
		val copied = a.copy(existingOwner = "T3")
		assertThat(copied.existingOwner).isEqualTo("T3")
		assertThat(copied.conflictingBlock).isSameInstanceAs(block)
	}

	@Test
	fun `AllPathsBlocked carries attempted count and supports equality`() {
		val a = PathReservationService.ReservationResult.AllPathsBlocked(3)
		val b = PathReservationService.ReservationResult.AllPathsBlocked(3)
		val c = PathReservationService.ReservationResult.AllPathsBlocked(5)

		assertThat(a).isEqualTo(b)
		assertThat(a).isNotEqualTo(c)
		assertThat(a.attemptedPaths).isEqualTo(3)
	}

	@Test
	fun `Success data class equality and reservedBlocks accessor`() {
		val sample = blocks.take(2)
		val s1 = PathReservationService.ReservationResult.Success(sample)
		val s2 = PathReservationService.ReservationResult.Success(sample)

		assertThat(s1).isEqualTo(s2)
		assertThat(s1.reservedBlocks).isEqualTo(sample)
	}

	@Test
	fun `NonContiguousStart carries startName and reason`() {
		val result =
			PathReservationService.ReservationResult.NonContiguousStart(
				"S1",
				"start S1 is not contiguous with the train footprint"
			)

		assertThat(result.startName).isEqualTo("S1")
		assertThat(result.reason).isEqualTo("start S1 is not contiguous with the train footprint")
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.NonContiguousStart>()
	}

	@Test
	fun `NonContiguousStart data class equality and hashCode`() {
		val a = PathReservationService.ReservationResult.NonContiguousStart("S1", "reason A")
		val b = PathReservationService.ReservationResult.NonContiguousStart("S1", "reason A")
		val c = PathReservationService.ReservationResult.NonContiguousStart("S1", "reason B")
		val d = PathReservationService.ReservationResult.NonContiguousStart("S2", "reason A")

		assertThat(a).isEqualTo(b)
		assertThat(a.hashCode()).isEqualTo(b.hashCode())
		assertThat(a).isNotEqualTo(c) // different reason
		assertThat(a).isNotEqualTo(d) // different startName
		// Exercise copy() + componentN of data class
		val copied = a.copy(reason = "reason C")
		assertThat(copied.reason).isEqualTo("reason C")
		assertThat(copied.startName).isEqualTo("S1")
	}

	@Test
	fun `NonContiguousStart is distinct from AllPathsBlocked`() {
		val ncs = PathReservationService.ReservationResult.NonContiguousStart("S1", "reason")
		val apb = PathReservationService.ReservationResult.AllPathsBlocked(3)

		// Distinct subtypes are never equal — guards against a future merge of the result types.
		assertThat(ncs).isNotEqualTo(apb)
		assertThat(ncs).isInstanceOf<PathReservationService.ReservationResult.NonContiguousStart>()
		assertThat(apb).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
	}
}
