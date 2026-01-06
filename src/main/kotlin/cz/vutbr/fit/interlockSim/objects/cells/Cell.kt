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
import java.util.Set;

/**
 * Cell in Grid
 *
 */
public interface Cell {
	/**
	 * Topology joins with other cells
	 *
	 * for drawing cells - "Segment analogy from displays"
	 */
	public enum Segment {
		/**
		 * left middle
		 */
		A(-1,0),
		/**
		 * left top
		 */
		B(-1,-1),
		/**
		 * center top
		 */
		C(0,-1),
		/**
		 * left bottom
		 */
		D(-1,1),
		/**
		 * right top
		 */
		E(1,-1),
		/**
		 * right middle
		 */
		F(1,0),
		/**
		 * right bottom
		 */
		G(1,1),
		/**
		 * center bottom
		 */
		H(0,1)
		;

		private final int dx, dy;

		private Segment(final int dx, final int dy) {
			this.dx = dx;
			this.dy = dy;
		}

		/**
		 * conversion
		 * @param d
		 * @return converted number
		 */
		public static final float d2r(int d) {
			return (d + 1)*0.5f;
		}

		/**
		 * conversion
		 * @param r
		 * @return converted number
		 */
		public static final int r2d(float r) {
			return (int) (2*r-1);
		}

		/**
		 * @return 0 - left, 0.5f - center, 1 - right
		 */
		public final float getRx() {
			return d2r(dx);
		}

		/**
		 * @return -1 - left, 0 - center, 1 - right
		 */
		public final int getDx() {
			return dx;
		}

		/**
		 * @return 0 - top, 0.5f - middle, 1 - bottom
		 */
		public final float getRy() {
			return d2r(dy);
		}

		/**
		 * @return -1 - top, 0 - middle, 1 - bottom
		 */
		public final int getDy() {
			return dy;
		}

		/**
		 * This is from d-coordinates conversion
		 * @param dx
		 * @param dy
		 * @return segment
		 */
		public static Segment segmentFor(int dx, int dy) {
			if (dx < 0) {
				if (dy < 0) return Cell.Segment.B;
				if (dy > 0) return Cell.Segment.D;
				return Cell.Segment.A;
			} else if (dx == 0) {
				if (dy < 0) return Cell.Segment.C;
				if (dy > 0) return Cell.Segment.H;
				return null;
			} else if (dx > 0) {
				if (dy < 0) return Cell.Segment.E;
				if (dy > 0) return Cell.Segment.G;
				return Cell.Segment.F;
			} else return null;
		}

		/**
		 * if segments can consist in regular cell
		 * @param a
		 * @param b
		 * @return if segments is good pair
		 */
		public static boolean conflict(Segment a, Segment b) {
			if (a == b) return true;
			if (a == null || b == null) return false;
			int dx = a.dx - b.dx;
			int dy = a.dy - b.dy;
			if (Math.sqrt(dx*dx+dy*dy) <= 0.5) return true;
			return false;
		}

		/**
		 * @param from
		 * @return neighbour Point
		 */
		public Point transform(final Point from) {
			assert from != null;
			final Point tr = (Point) from.clone();
			tr.x += getDx();
			tr.y += getDy();
			assert !from.equals(tr) : this;
			return tr;
		}

		/**
		 * @param segment
		 * @return segment in reverse direction
		 */
		public static Segment anti(Segment segment) {
			final Segment anti = segmentFor(-segment.getDx(), -segment.getDy());
			assert anti != null;
			return anti;
		}
	}

	/**
	 * "Natoceni bunky" - "pro prehlednednejsi vytvareni bunek"
	 */
	public enum SpatialType {//!!! prvni segment pro false orientaci !!!
		/**
		 *
		 */
				VERTICAL(Segment.H, Segment.C),
		/**
		 *
		 */
				HORIZONTAL(Segment.F, Segment.A),
		/**
		 *
		 */
				DIAGONAL1(Segment.E, Segment.D), //  /
		/**
		 *
		 */
				DIAGONAL2(Segment.G, Segment.B)  //  \
		;

		private final Segment[] segments;

		SpatialType(final Segment s1, final Segment s2) {
			this.segments = new Segment[]{s1,s2};
		}

		/**
		 * @return segments which created this enum
		 */
		public Segment[] getSegments() {
			return segments;
		}
	}

	/**
	 *
	 * @return {@link SpatialType}
	 */
	public SpatialType getSpatialType();

	/**
	 * @return Possible joins
	 */
	public Set<Segment> joins();
}
