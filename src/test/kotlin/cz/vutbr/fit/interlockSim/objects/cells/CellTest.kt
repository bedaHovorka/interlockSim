/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells;

import java.awt.Point;
import java.util.EnumMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType;

/**
 * This class checks cells atributes (mainly topology)
 */
@SuppressWarnings("unchecked")
public class CellTest {

	private static final Class<? extends Cell>[] TESTED_CLASSES = new Class[]{RailSemaphore.class, InOut.class};

	/**
	 *
	 *
	 */
	@Test
	public void testSegments() {
		final Point center = new Point(0, 0);
		final EnumMap<Segment, Point> points = new EnumMap<Segment, Point>(Segment.class);

		for (Segment s : Segment.values()) {
			final int dx = s.getDx();
			final int dy = s.getDy();
			final float rx = s.getRx();
			final float ry = s.getRy();

			assertThat(Segment.r2d(rx)).isEqualTo(dx);
			assertThat(Segment.r2d(ry)).isEqualTo(dy);
			assertThat(Segment.d2r(dx)).isEqualTo(rx);
			assertThat(Segment.d2r(dy)).isEqualTo(ry);
			assertThat(Segment.segmentFor(dx, dy)).isSameAs(s);

			assertThat(Segment.anti(Segment.anti(s))).isSameAs(s);
			assertThat(Segment.conflict(s, s)).isTrue();

			final Point tr = s.transform(center);
			assertThat(tr).isNotSameAs(center); //nesmi zmenit a vracet predany objekt
			assertThat(center.equals(tr)).as("transformed point is equal").isFalse();
			assertThat(points.values().contains(tr)).as("transformed point is genereted twice").isFalse();
			points.put(s, tr);
		}
	}

	/**
	 *
	 *
	 */
	@Test
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

				assertThat(sem1.direction())
					.as("direction for class " + clazz.getSimpleName() +  " and " + t.toString())
					.isSameAs(Segment.anti(sem2.direction()));
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

		fail("Unexpected cell class: " + clazz + ", objects: " + java.util.Arrays.toString(objects));
		return null;
	}
}
