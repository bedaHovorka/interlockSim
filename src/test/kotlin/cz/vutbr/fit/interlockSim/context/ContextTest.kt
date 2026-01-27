/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Context testing
 *
 */
class ContextTest : KoinTestBase() {
	private val editingContextFactory: cz.vutbr.fit.interlockSim.context.EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()
	private lateinit var context: SimulationContext
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val tl: SimpleTrackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
	private val rs1: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	@BeforeEach
	fun setUp() {
		// Build network using editing context
		editingContextFactory.createEmptyContext().use { editingContext ->
			val pA = Point(1, 1)
			val r1 = Point(4, 2)
			val pB = Point(5, 5)
			editingContext.putCell(pA, inA)
			editingContext.putCell(pB, outB)
			editingContext.putCell(r1, rs1)
			editingContext.joinCells(r1, pB, tl)
			editingContext.joinCells(pA, r1, tl)

			// Convert to simulation context for testing
			context = simulationContextFactory.createContext(editingContext)
			testContext = context  // Track for cleanup
		} // editing context automatically closed here
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#getNextTrackBlock(cz.vutbr.fit.interlockSim.objects.cells.NodeCell, cz.vutbr.fit.interlockSim.objects.core.TrackBlock)}.
	 */
	@Test
	fun testGetNextTrackBlock() {
		// Get the dynamic track block from simulation context graph
		val dynamicBlock = context.getNextTrackBlock(inA, null)

		// When current is null, should return the first connected track block
		assertThat(dynamicBlock).isNotNull()
		assertThat(context.getNextTrackBlock(outB, null)).isNotNull()

		// When current is a track block, the method tries to find the next segment
		// But with SimpleTrackBlock which only has one section, there is no following segment
		// So it returns null
		assertThat(context.getNextTrackBlock(inA, dynamicBlock)).isNull()
		assertThat(context.getNextTrackBlock(outB, dynamicBlock)).isNull()
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#getNextTrackSection(PathSeparator, TrackSection)}
	 */
	@Test
	fun testGetNextTrackSection() {
		// When current is null, getNextTrackSection gets the first track block
		// and calls getNextTrackSection on it with null, which returns the block itself
		val nextFromA = context.getNextTrackSection(inA, null)
		assertThat((nextFromA as DynamicTrackBlock).staticRef).isSameInstanceAs(tl)

		val nextFromB = context.getNextTrackSection(outB, null)
		assertThat((nextFromB as DynamicTrackBlock).staticRef).isSameInstanceAs(tl)

		// When current is a track section (the SimpleTrackBlock acts as its own section),
		// SimpleTrackBlock.getNextTrackSection() returns null because it only has one section
		// Then it tries to get the next track block, which returns null because there's no following segment
		assertThat(context.getNextTrackSection(inA, tl)).isNull()
		assertThat(context.getNextTrackSection(outB, tl)).isNull()
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#isSeparatorInDirection(cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator, cz.vutbr.fit.interlockSim.objects.core.Track, cz.vutbr.fit.interlockSim.objects.core.Track)}
	 */
	@Test
	fun testIsSeparatorInDirection() {
		assertThat(context.isSeparatorInDirection(inA, null, tl)).isTrue()
		assertThat(context.isSeparatorInDirection(outB, null, tl)).isTrue()
		assertThat(context.isSeparatorInDirection(inA, tl, null)).isTrue()
		assertThat(context.isSeparatorInDirection(outB, tl, null)).isTrue()
		// TODO semafory
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#pathToNextSemaphore(PathSeparator, TrackSection)}
	 */
	@Test
	fun testPathToNextSemaphore() {
		// Get Dynamic wrappers for InOut objects (simulation context uses Dynamic wrappers)
		val inOuts = context.getInOuts()
		val dynamicInA = inOuts.find { it.name == "A" }!!
		val dynamicOutB = inOuts.find { it.name == "B" }!!

		val arrayPath = ArrayPath(context)
		arrayPath.add(dynamicInA)
		arrayPath.add(tl)
		arrayPath.add(dynamicOutB)
		val pathFromInA = context.pathToNextSemaphore(dynamicInA, tl)
		assertThat(pathFromInA).isNotNull()
		assertThat(arrayPath.equalsWithElements(pathFromInA!!)).isTrue()
		val pathFromOutB = context.pathToNextSemaphore(dynamicOutB, tl)
		assertThat(pathFromOutB).isNotNull()
		assertThat(arrayPath.reversePath().equalsWithElements(pathFromOutB!!)).isTrue()
		// Note: Null parameter checks from Java version are now handled by Kotlin's type system at compile time
	}
}
