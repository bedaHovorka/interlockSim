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

import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Point

/**
 * Context testing
 *
 */
class ContextTest {
	private val context: DefaultContext = XMLContextFactory.getInstance().createEmptyContext()
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val tl: SimpleTrackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
	private val rs1: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	@BeforeEach
	fun setUp() {
		val pA = Point(1, 1)
		val r1 = Point(4, 2)
		val pB = Point(5, 5)
		context.putCell(pA, inA)
		context.putCell(pB, outB)
		context.putCell(r1, rs1)
		context.joinCells(r1, pB, tl)
		context.joinCells(pA, r1, tl)
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#getNextTrackBlock(cz.vutbr.fit.interlockSim.objects.cells.NodeCell, cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock)}.
	 */
	@Test
	fun testGetNextTrackBlock() {
		// When current is null, should return the first connected track block
		assertThat(context.getNextTrackBlock(inA, null)).isSameAs(tl)
		assertThat(context.getNextTrackBlock(outB, null)).isSameAs(tl)

		// When current is a track block, the method tries to find the next segment
		// But with SimpleTrackBlock which only has one section, there is no following segment
		// So it returns null
		assertThat(context.getNextTrackBlock(inA, tl)).isNull()
		assertThat(context.getNextTrackBlock(outB, tl)).isNull()
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#getNextTrackSection(PathSeparator, TrackSection)}
	 */
	@Test
	fun testGetNextTrackSection() {
		// When current is null, getNextTrackSection gets the first track block
		// and calls getNextTrackSection on it with null, which returns the block itself
		val nextFromA = context.getNextTrackSection(inA, null)
		assertThat(nextFromA).isSameAs(tl)

		val nextFromB = context.getNextTrackSection(outB, null)
		assertThat(nextFromB).isSameAs(tl)

		// When current is a track section (the SimpleTrackBlock acts as its own section),
		// SimpleTrackBlock.getNextTrackSection() returns null because it only has one section
		// Then it tries to get the next track block, which returns null because there's no following segment
		assertThat(context.getNextTrackSection(inA, tl)).isNull()
		assertThat(context.getNextTrackSection(outB, tl)).isNull()
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#isSeparatorInDirection(cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator, cz.vutbr.fit.interlockSim.objects.tracks.Track, cz.vutbr.fit.interlockSim.objects.tracks.Track)}
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
		val arrayPath = ArrayPath(context)
		arrayPath.add(inA)
		arrayPath.add(tl)
		arrayPath.add(outB)
		val pathFromInA = context.pathToNextSemaphore(inA, tl)
		assertThat(pathFromInA).isNotNull()
		assertThat(arrayPath.equalsWithElements(pathFromInA!!)).isTrue()
		val pathFromOutB = context.pathToNextSemaphore(outB, tl)
		assertThat(pathFromOutB).isNotNull()
		assertThat(arrayPath.reversePath().equalsWithElements(pathFromOutB!!)).isTrue()
		// Note: Null parameter checks from Java version are now handled by Kotlin's type system at compile time
	}
}
