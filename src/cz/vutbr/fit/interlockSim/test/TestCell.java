/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.test;

import java.awt.Point;
import java.util.EnumMap;

import junit.framework.TestCase;
import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.OrientedNodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType;

/**
 * This class checks cells atributes (mainly topology)
 */
@SuppressWarnings("unchecked")
public class TestCell extends TestCase {
	
	private static final Class<? extends Cell>[] TESTED_CLASSES = new Class[]{RailSemaphore.class, InOut.class};
    
	/**
     * 
     *
     */
	public void testSegments() {
		final Point center = new Point(0, 0);
		final EnumMap<Segment, Point> points = new EnumMap<Segment, Point>(Segment.class);
		
		for (Segment s : Segment.values()) {
			final int dx = s.getDx();
			final int dy = s.getDy();
			final float rx = s.getRx();
			final float ry = s.getRy();
			
			assertEquals(dx, Segment.r2d(rx));
			assertEquals(dy, Segment.r2d(ry));
			assertEquals(rx, Segment.d2r(dx));
			assertEquals(ry, Segment.d2r(dy));
			assertSame(s, Segment.segmentFor(dx, dy));
			
			assertSame(s, Segment.anti(Segment.anti(s)));
			assertTrue(Segment.conflict(s, s));
			
			final Point tr = s.transform(center);
			assertNotSame(center, tr); //nesmi zmenit a vracet predany objekt
			assertFalse("transformed point is equal", center.equals(tr));
			assertFalse("transformed point is genereted twice", points.values().contains(tr));
			points.put(s, tr);
		}
	}
	
	/**
	 * 
	 *
	 */
	public void testDirection() {
		for (Class<? extends Cell> clazz : TESTED_CLASSES) {
			testDir(clazz);
		}
	}

	private void testDir(Class<? extends Cell> clazz, Object... objects) {
		for (SpatialType t : SpatialType.values()) {
			try {
				final OrientedNodeCell sem1 = newCell(clazz, true, t, objects);
				final OrientedNodeCell sem2 = newCell(clazz, false, t, objects);
				
				assertSame("direction for class " + clazz.getSimpleName() +  " and " + t.toString(), 
						sem1.direction(), Segment.anti(sem2.direction()));
			} catch (IllegalArgumentException e) {
				final String message = e.getMessage();
				if (message != null && message.equals(RailSwitch.UNSUPORTED_SWITCH_TYPES_MESSAGE)) {
					continue;
				}
				throw e;
			}
		}
	}

	private OrientedNodeCell newCell(Class<? extends Cell> clazz, boolean o, SpatialType t, Object... objects) {
		if (clazz == RailSemaphore.class) {
			return new RailSemaphore(o,t);
		} else if (clazz == InOut.class) {
			return new InOut("xx", o, t);
		}
		
		assert false : objects;
		return null;
	}
}
