/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import java.awt.Point;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

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
public class ContextTest {
	private final DefaultContext context = XMLContextFactory.getInstance().createEmptyContext();
	private final InOut inA = new InOut("A", false, SpatialType.HORIZONTAL);
	private final InOut outB = new InOut("B", true, SpatialType.HORIZONTAL);
	private final SimpleTrackBlock tl = new SimpleTrackBlock(inA, outB, 1000, 80);
	private final RailSemaphore rs1 = new RailSemaphore(false, SpatialType.DIAGONAL1);

	@BeforeEach
	protected void setUp() throws Exception {
		final Point pA = new Point(1,1); final Point r1 = new Point(4,2); final Point pB = new Point(5,5);
		context.putCell(pA, inA); context.putCell(pB, outB); context.putCell(r1, rs1);
		context.joinCells(r1, pB, tl);
		context.joinCells(pA, r1, tl);
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#getNextTrackBlock(cz.vutbr.fit.interlockSim.objects.cells.NodeCell, cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock)}.
	 */
	@Test
	public void testGetNextTrackBlock() {
		assertThat(context.getNextTrackBlock(inA, null)).isSameAs(tl);
		assertThat(context.getNextTrackBlock(outB, null)).isSameAs(tl);
		assertThat(context.getNextTrackBlock(inA, tl)).isNull();
		assertThat(context.getNextTrackBlock(outB, tl)).isNull();
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#getNextTrackSection(PathSeparator, TrackSection)}
	 */
	@Test
	public void testGetNextTrackSection() {
		assertThat(context.getNextTrackSection(inA, null)).isSameAs(tl);
		assertThat(context.getNextTrackSection(outB, null)).isSameAs(tl);
		assertThat(context.getNextTrackSection(inA, tl)).isNull();
		assertThat(context.getNextTrackSection(outB, tl)).isNull();
	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#isSeparatorInDirection(cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator, cz.vutbr.fit.interlockSim.objects.tracks.Track, cz.vutbr.fit.interlockSim.objects.tracks.Track)}
	 */
	@Test
	public void testIsSeparatorInDirection() {
		assertThat(context.isSeparatorInDirection(inA, null, tl)).isTrue();
		assertThat(context.isSeparatorInDirection(outB, null, tl)).isTrue();
		assertThat(context.isSeparatorInDirection(inA, tl, null)).isTrue();
		assertThat(context.isSeparatorInDirection(outB, tl, null)).isTrue();
		//TODO semafory

	}

	/**
	 * Test method for {@link cz.vutbr.fit.interlockSim.context.SimulationContext#pathToNextSemaphore(PathSeparator, TrackSection)}
	 */
	@Test
	public void testPathToNextSemaphore() {
		final ArrayPath arrayPath = new ArrayPath(context);
		arrayPath.addAll(inA, tl, outB);
		assertThat(arrayPath.equalsWithElements(context.pathToNextSemaphore(inA, tl))).isTrue();
		assertThat(arrayPath.reversePath().equalsWithElements(context.pathToNextSemaphore(outB, tl))).isTrue();
		assertPathToNextSemaphoreTrowExeption(inA, null);
		assertPathToNextSemaphoreTrowExeption(outB, null);
	}

	private void assertPathToNextSemaphoreTrowExeption(PathSeparator separator, TrackSection section) {
		assertThatThrownBy(() -> context.pathToNextSemaphore(separator, section))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
