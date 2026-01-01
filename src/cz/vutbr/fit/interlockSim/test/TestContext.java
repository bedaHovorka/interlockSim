/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.test;

import java.awt.Point;

import junit.framework.TestCase;
import cz.vutbr.fit.interlockSim.context.DefaultContext;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType;
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection;
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory;

/**
 * Context testing
 *
 */
public class TestContext extends TestCase {
	private final DefaultContext context = XMLContextFactory.getInstance().createEmptyContext();
	private final InOut inA = new InOut("A", false, SpatialType.HORIZONTAL);
	private final InOut outB = new InOut("B", true, SpatialType.HORIZONTAL);
	private final SimpleTrackBlock tl = new SimpleTrackBlock(inA, outB, 1000, 80);
	private final RailSemaphore rs1 = new RailSemaphore(false, SpatialType.DIAGONAL1);

	@Override
	protected void setUp() throws Exception {
		final Point pA = new Point(1,1); final Point r1 = new Point(4,2); final Point pB = new Point(5,5);
		context.putCell(pA, inA); context.putCell(pB, outB); context.putCell(r1, rs1);
		context.joinCells(r1, pB, tl);
		context.joinCells(pA, r1, tl);
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#getNextTrackBlock(cz.vutbr.fit.interlockSim.objects.cells.NodeCell, cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock)}.
	 */
	public void testGetNextTrackBlock() {
		assertSame(tl, context.getNextTrackBlock(inA, null));
		assertSame(tl, context.getNextTrackBlock(outB, null));
		assertSame(null, context.getNextTrackBlock(inA, tl));
		assertSame(null, context.getNextTrackBlock(outB, tl));
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#getNextTrackSection(PathSeparator, TrackSection)}
	 */
	public void testGetNextTrackSection() {
		assertSame(tl, context.getNextTrackSection(inA, null));
		assertSame(tl, context.getNextTrackSection(outB, null));
		assertSame(null, context.getNextTrackSection(inA, tl));
		assertSame(null, context.getNextTrackSection(outB, tl));
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#isSeparatorInDirection(cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator, cz.vutbr.fit.interlockSim.objects.tracks.Track, cz.vutbr.fit.interlockSim.objects.tracks.Track)}
	 */
	public void testIsSeparatorInDirection() {
		assertTrue(context.isSeparatorInDirection(inA, null, tl));
		assertTrue(context.isSeparatorInDirection(outB, null, tl));
		assertTrue(context.isSeparatorInDirection(inA, tl, null));
		assertTrue(context.isSeparatorInDirection(outB, tl, null));
		//TODO semafory

	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#pathToNextSemaphore(PathSeparator, TrackSection)}
	 */
	public void testPathToNextSemaphore() {
		final ArrayPath arrayPath = new ArrayPath(context);
		arrayPath.addAll(inA, tl, outB);
		assertTrue(arrayPath.equalsWithElements(context.pathToNextSemaphore(inA, tl)));
		assertTrue(arrayPath.reversePath().equalsWithElements(context.pathToNextSemaphore(outB, tl)));
		assertPathToNextSemaphoreTrowExeption(inA, null);
		assertPathToNextSemaphoreTrowExeption(outB, null);
	}

	private void assertPathToNextSemaphoreTrowExeption(PathSeparator separator, TrackSection section) {
		try {
			context.pathToNextSemaphore(separator, section);
			fail("didn't throw exeption");
		} catch (IllegalArgumentException e) {
			//EMPTY
		}
	}
}
