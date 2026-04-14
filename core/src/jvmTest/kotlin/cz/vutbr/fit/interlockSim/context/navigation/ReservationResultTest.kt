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
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.InputStream

/**
 * Smoke tests for [PathReservationService.ReservationResult] subtypes that are
 * instantiable but were not exercised by existing tests (NoPathExists, Conflict).
 */
@DisplayName("PathReservationService.ReservationResult (Issue #453)")
class ReservationResultTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var blocks: List<DynamicTrackBlock>

	@BeforeEach
	fun setUp() {
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")
		val editing = editingContextFactory.createContext(xmlStream) as EditingContext
		val simCtx = simulationContextFactory.createContext(editing) as DefaultSimulationContext
		val inOuts = simCtx.getInOuts().toList()
		val navigator: TopologyNavigator = simCtx.scope.get()
		blocks =
			navigator
				.findAllTopologicalPaths(inOuts[0], inOuts[1])
				.first()
				.mapNotNull { section ->
					val block = section.getTrackBlock()
					if (block is DynamicTrackBlock) block else null
				}.distinct()
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
}
