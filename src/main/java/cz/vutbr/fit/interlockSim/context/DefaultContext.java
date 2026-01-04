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

import static cz.vutbr.fit.interlockSim.util.Util.assertNodeCell;
import jDisco.DiscoException;
import jDisco.Process;
import jDisco.Random;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Set;

import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath;
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator;
import cz.vutbr.fit.interlockSim.objects.paths.Path;
import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.Track;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection;
import cz.vutbr.fit.interlockSim.sim.Generator;
import cz.vutbr.fit.interlockSim.sim.InOutWorker;
import cz.vutbr.fit.interlockSim.sim.LoopProcess;
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop;
import cz.vutbr.fit.interlockSim.sim.SimulationException;
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph;
import cz.vutbr.fit.interlockSim.util.HashMapGraph;
import cz.vutbr.fit.interlockSim.util.TreeMultiMap;
import cz.vutbr.fit.interlockSim.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * implementation of {@link EditingContext} and {@link SimulationContext}
 */
public abstract class DefaultContext extends Observable implements EditingContext, SimulationContext { //konecne rozdelit?
	/**
	 * Logger for general class operations.
	 */
	private static final Logger logger = LoggerFactory.getLogger(DefaultContext.class);

	/**
	 * Separate logger for simulation events to allow independent level control.
	 * Configured in logback.xml as "cz.vutbr.fit.interlockSim.simulation".
	 */
	private static final Logger simulationLogger = LoggerFactory.getLogger("cz.vutbr.fit.interlockSim.simulation");

	/**
	 * Constant - square root of 2
	 * 1.4142135623730951
	 */
	public static final double SQRT2 = Math.sqrt(2);
	private final ExtendedUnorientedGraph<Point, TrackBlock, Segment> extendedUnorientedGraph = new HashMapGraph<Point, TrackBlock, Segment>();
	private final Map<TrackBlock, Set<Point>> linesKeys= new IdentityHashMap<TrackBlock, Set<Point>>();
	private final Set<ReportType> allowedReportTypes = EnumSet.noneOf(ReportType.class);
	private ArrayList<InOut> inouts = new ArrayList<InOut>(2);
	private Map<InOut, InOutWorker> workers = new IdentityHashMap<InOut, InOutWorker>();
	private double currentMaxSpeed = PathElement.COMMON_MAX_SPEED;
	private double currentTrackLength = Track.COMMON_TRACK_LENGTH;
	private final DefaultRailWayNetGrid railwayNetGrid;
	private String nameString;
	private LoopProcess mainProcess;

	protected DefaultContext(int cols, int rows) {
		this.railwayNetGrid = new DefaultRailWayNetGrid(cols, rows);
		logger.debug("Initialized railway network grid: {}x{} cells", cols, rows);
	}

	public DefaultRailWayNetGrid getRailWayNetGrid() {
		return railwayNetGrid;
	}

	private void swapXY(Point p) {
		int temp = p.x;
		p.x = p.y;
		p.y = temp;
	}

	private class Tranporter {

		private final Point p2;
		private final Segment s2;
		private final Segment s1;
		private final Point p1;

		Tranporter(Point p1, Point p2, Segment s1, Segment s2) {
			this.p1 = p1;
			this.p2 = p2;
			this.s1 = s1;
			this.s2 = s2;
		}

		final Point getP1() {
			return p1;
		}

		final Point getP2() {
			return p2;
		}

		final Segment getS1() {
			return s1;
		}

		final Segment getS2() {
			return s2;
		}

	}

	private Map<Point, TrackBlockPart> findTrackLineParts(final Point key1,final Point key2, final TrackBlock trackBlock) {
		final TreeMultiMap<Double, Tranporter> treeMM = new TreeMultiMap<Double, Tranporter>();

		if (key1.distance(key2) <= SQRT2) return null;

		NodeCell nodecell1 = assertNodeCell(getGrid().get(key1));
		NodeCell nodecell2 = assertNodeCell(getGrid().get(key2));

		for (Segment s1 : nodecell1.joins()) {
			assert s1 != null : nodecell1;
			final Point p1 = s1.transform(key1);
			if (used(p1)) continue;
			for (Segment s2 : nodecell2.joins()) {
				assert s2 != null : nodecell2;
				if (s1 == s2) continue; //stejne segmenty se nepropoji
				final Point p2 = s2.transform(key2);
				if (used(p2)) continue;
				final double distance = p1.distance(p2);
				//if (distance <= 1) continue;
				treeMM.put(distance, new Tranporter(p1, p2, s1, s2));
			}
		}


		for (Tranporter t : treeMM.values()) {
			final Map<Point, TrackBlockPart> tryJoin = tryJoin(t, key1, key2, trackBlock);
			if (tryJoin != null) return tryJoin;
		}
		return null;
	}

	/**
	 * find edge cells
	 * if is known from nodes joins to edge
	 * @param s1 edge join to node 1
	 * @param s2 edge join to node 2
	 * @param key1 location of node 1
	 * @param key2 location of node 2
	 * @param trackBlock edge object
	 * @return success of inserting
	 */
	public boolean hardJoin(final Segment s1, final Segment s2, final Point key1, final Point key2, final TrackBlock trackBlock) {
		assert key1 != null && key2 != null;
		if (key1.distance(key2) > SQRT2) {
			final Point blockEndFrom = s1.transform(key1);
			final Point blockEndTo = s2.transform(key2);
			return tryJoin(blockEndFrom, blockEndTo, s1, s2, key1, key2, trackBlock) != null;
		}
		extendedUnorientedGraph.put(key1, s1, key2, s2, trackBlock);
		return extendedUnorientedGraph.get(key1, key2) == trackBlock;
	}

	private Map<Point, TrackBlockPart> tryJoin(final Tranporter t, final Point key1, final Point key2, final TrackBlock trackBlock) {
		return tryJoin(t.getP1(), t.getP2(), t.getS1(), t.getS2(), key1, key2, trackBlock);
	}

	private Map<Point, TrackBlockPart> tryJoin(final Point pi1, final Point pi2, final Segment s1, final Segment s2, final Point key1, final Point key2, final TrackBlock trackBlock) {
		if (pi1 == null || pi2 == null) throw new IllegalArgumentException();
		Point p1 = (Point) pi1.clone();
		Point p2 = (Point) pi2.clone();

		final List<Point> keys = new ArrayList<Point>();
		boolean b = bresenham(key1, key2, p1, p2, keys);
		if (keys.size() <= 0) return null;

		final Map<Point, TrackBlockPart> buildPath = (b)
			? buildPath(keys, key2, key1, trackBlock)
					: buildPath(keys, key1, key2, trackBlock);
		if (buildPath != null && buildPath.size() > 0) {
			getGrid().putMap(buildPath);
			linesKeys.put(trackBlock, buildPath.keySet());
			assert !extendedUnorientedGraph.contains(key1, key2);
			extendedUnorientedGraph.put(key1, s1, key2, s2, trackBlock);
			return buildPath;
		}
		return null;
	}

	private Map<Point, TrackBlockPart> buildPath(List<Point> bresenham, final Point key1, final Point key2, final TrackBlock trackBlock) {
		assert bresenham != null && bresenham.size() > 0;
		final LinkedHashMap<Point, TrackBlockPart> map = new LinkedHashMap<Point, TrackBlockPart>();
		Point from = key1;
		Point middle = bresenham.get(0);
		TrackBlockPart part;

		if (bresenham.size() > 1) {
			Point to;
			for (int i = 1; i < bresenham.size(); i++) {
				to = bresenham.get(i);

				part = createPart(from, middle, to, trackBlock);
				if (part == null) return null;
				map.put(middle, part);

				from = middle;
				middle = to;
			}
		}
		part = createPart(from, middle, key2, trackBlock);
		if (part == null) return null;
		map.put(middle, part);
		return map;
	}

	private TrackBlockPart createPart(Point from, Point middle, Point to, TrackBlock block) {
		assert block != null;
		if (used(middle)) return null;
		if (from.equals(to) || from.equals(middle) || middle.equals(to)) return null;

		int ux = from.x - middle.x; int uy = from.y - middle.y;
		int vx = to.x - middle.x; int vy = to.y - middle.y;

		if (Math.abs(ux) > 1 || Math.abs(uy) > 1 || Math.abs(vx) > 1 || Math.abs(vy) > 1) return null;

		Segment s1 = Cell.Segment.segmentFor(ux, uy);
		Segment s2 = Cell.Segment.segmentFor(vx, vy);
		//vyhodit nehezky dvojice segmentu
		if (s1 == null || s2 == null || Cell.Segment.conflict(s1, s2)) return null;
		return new TrackBlockPart(block, s1, s2);
	}

	private boolean bresenham(final Point key1, final Point key2, Point p1, Point p2, List<Point> points) {
		//p1 = (Point) p1.clone();
		//p2 = (Point) p2.clone();
		assert (key1 != null && key2 != null && p1 != null && p2 != null);
		assert (!key1.equals(p1) && !key2.equals(p2) && !key1.equals(p2) && !key2.equals(p1));

		if (p1.equals(p2)) {
			points.add(p1); //snad je naklonovany
			return false;
		}

		int dx = Math.abs(p2.x-p1.x), dy = Math.abs(p2.y-p1.y);
		boolean swapped = dy > dx;

		if (swapped) {
			swapXY(p1);
			swapXY(p2);
			int temp = dx;
			dx = dy;
			dy = temp;
		}

		final boolean b = p1.x > p2.x;
		if (b) {
			Point temp = p1;
			//assign to paramenters is part of algorithm
			p1 = p2;
			p2 = temp;
		}

		int P = 2*dy - dx;//prediktor
		int P1 = 2*dy, P2 = P1 - 2*dx;
		int y = p1.y;

		int step_y = (p1.y > p2.y) ? -1: 1;//smer kresleni

		for (int x = p1.x; x <= p2.x; x++) {
			final Point newPoint = (!swapped) ? new Point(x, y) : new Point(y, x);

			if (newPoint.equals(key1) || newPoint.equals(key2) || used(newPoint)) {
				points.clear();
				return b;
			}
			points.add(newPoint);

			//nastaveni prediktoru
			if (P >= 0) {
				P += P2; y += step_y;
			} else {
				P += P1;
			}
		}
		return b;
	}

	private boolean used(final Point newPoint) {
		return getGrid().containsKey(newPoint);
	}

	public void joinCells(Point key1, Point key2, TrackBlock trackBlock) {
		//pokusit se nakreslit primku
		final Map<Point, TrackBlockPart> lineParts = findTrackLineParts(key1, key2, trackBlock);

		if(lineParts == null || lineParts.size() == 0) {
			logger.debug("Join failed between ({},{}) and ({},{})", key1.x, key1.y, key2.x, key2.y);
			setChanged();
			notifyObservers("Join not success");
			return;
		}

		logger.debug("Created track join ({},{})->({},{}) with {} intermediate cells",
			key1.x, key1.y, key2.x, key2.y, lineParts.size());
		setChanged();
		notifyObservers("Join created");
	}

	public void putCell(Point key, NodeCell nodeCell) {
		assert key != null;
		assert key.x >= 0 && key.y >= 0 && key.x <= railwayNetGrid.getCols() && key.y <= railwayNetGrid.getRows();
		if (getGrid().put(key, nodeCell) == nodeCell) return;

		// vedlejsi Nody (sousedni bunky)
		for (Segment s1 : nodeCell.joins()) {
			assert s1 != null : nodeCell;
			final Point p = s1.transform(key);
			final Cell cell2 = getGrid().get(p);
			if (!(cell2 instanceof NodeCell)) continue;
			final NodeCell nodeCell2 = (NodeCell) cell2;

			//vzit proti-segment
			final Segment s2 = Segment.anti(s1);
			if (nodeCell2.joins().contains(s2)) {
				assert s2.transform(p).equals(key);
				extendedUnorientedGraph.putIfNotExists(key, s1, p, s2, new SimpleTrackBlock(nodeCell, nodeCell2, Track.MIN_LENGTH, getCurrentMaxSpeed()));
			}
		}
		if (nodeCell instanceof InOut) getInOuts().add((InOut) nodeCell);
		setChanged();
		notifyObservers(key);
		if (logger.isTraceEnabled()) {
			logger.trace("Added {} at ({},{})", nodeCell.getClass().getSimpleName(), key.x, key.y);
		}
	}

	private DefaultRailWayNetGrid getGrid() {
		return railwayNetGrid;
	}

	public void removeCell(Point key) {
		final Cell cell = getGrid().get(key);
		if (cell instanceof NodeCell) {
			getGrid().remove(key);
			for (TrackBlock tl : extendedUnorientedGraph.removeAll(key)) {
				final Collection<Point> set = linesKeys.get(tl);
				if (set != null) getGrid().keySet().removeAll(set);
			}
			setChanged();
			notifyObservers("Cell removed");
		}
	}

	public void removeLine(TrackBlock line) {
		assert line != null;
		extendedUnorientedGraph.remove(line);
		getGrid().keySet().removeAll(linesKeys.remove(line));
		setChanged();
		notifyObservers("TrackBlock removed");
	}

	public void moveCell(Point from, Point to) {
		final Cell fromCell = getGrid().get(from);
		if (!(fromCell instanceof NodeCell)) return;

		final Cell toCell = getGrid().get(to);
		if (toCell != null) return;

		putCell(to, (NodeCell)fromCell);
		removeCell(from);
	}

	public Segment getSegment(final PathSeparator separator, final Track track, final Track secondEndTrack) {
		if (track != null) return getSegment(separator, track);
		assert separator != null;
		assert secondEndTrack != null : separator;
		assert separator instanceof OrientedPathSeparator;//Util
		final Segment segment = getSegment(separator, secondEndTrack);
		return separator.getFollowingSegment(segment);//pro OrientedPS je to napevno...
	}

	public Segment getSegment(final PathSeparator separator, final Track track) {
		if (separator == null || track == null) throw new IllegalArgumentException("separator or track is null");
		else if (track instanceof TrackSection) {
			return getSegment(separator, (TrackSection) track);
		}
		return getSegment(assertNodeCell(separator), Util.assertInstanceOf(TrackBlock.class, track));
	}

	/**
	 * @param separator
	 * @param section
	 * @return pseudo join segment in block
	 */
	public Segment getSegment(final PathSeparator separator, final TrackSection section) {
		if (section == null) {
			throw new IllegalArgumentException("section is null");
		}
		final TrackBlock trackBlock = section.getTrackBlock();
		if (trackBlock.isInnerElement(separator)) return trackBlock.getJoin(separator, section);
		return getSegment(assertNodeCell(separator), trackBlock);
	}

	private Segment getSegment(final NodeCell node, final TrackBlock current) { // nodeCell v graphu...
		final Point location = getLocation(node);
		return getSegment(location, current);
	}

	private Segment getSegment(final Point location, final TrackBlock current) {
		assert current==null || getGraph().get(location).contains(current) : current;
		return current==null ? null : getGraph().extensionalObject(location, current);
	}

	private Point getLocation(NodeCell node) {
		final Point location = getRailWayNetGrid().getLocation(node);
		assert location != null : this;
		return location;
	}

	public final TrackBlock getNextTrackBlock(final NodeCell nodeCell, final TrackBlock current) {
		if (nodeCell == null) throw new IllegalArgumentException("node is null");
		final Point location = getLocation(nodeCell);
		final Segment segment = getSegment(location, current);
		final Segment followingSegment = nodeCell.getFollowingSegment(segment);
		if (followingSegment == null) return null;

		final Map<Segment, TrackBlock> assignedEdges = getGraph().assignedEdges(location);
		return assignedEdges.get(followingSegment);
	}

	public TrackSection getNextTrackSection(PathSeparator separator, TrackSection current) {
		if (separator == null) throw new IllegalArgumentException("separtor is null");
		TrackBlock trackBlock = null;
		if (current != null) {
			trackBlock = current.getTrackBlock();
			assert trackBlock != null;
			final TrackSection nextTrackSection = trackBlock.getNextTrackSection(separator, current);
			if (nextTrackSection != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("getNextTrackSection: found next section within same block from {}", separator);
				}
				return nextTrackSection;
			}
		}

		//z dalsi TrackBlock
		final NodeCell nodeCell = assertNodeCell(separator);
		final TrackBlock nextTrackBlock = getNextTrackBlock(nodeCell, trackBlock);
		final TrackSection result = nextTrackBlock==null ? null : nextTrackBlock.getNextTrackSection(nodeCell, null);
		if (logger.isTraceEnabled()) {
			logger.trace("getNextTrackSection: navigating network from {}, result: {}", separator, result != null ? "found" : "not found");
		}
		return result;
	}


	public void run() throws EmptyContextException, SimulationException {
		if (getGraph().isEmpty() || getGrid().isEmpty() || inouts.isEmpty()) {
			logger.warn("Cannot start simulation: graph={}, grid={}, inouts={}",
				getGraph().isEmpty() ? "empty" : "ok",
				getGrid().isEmpty() ? "empty" : "ok",
				inouts.isEmpty() ? "empty" : "ok");
			throw new EmptyContextException();
		}
		if (mainProcess == null) mainProcess = new Generator(this);

		logger.info("Starting simulation: {} InOut points, {} track blocks, main process={}",
			inouts.size(), getGraph().size(), mainProcess.getClass().getSimpleName());

		for (InOut i : inouts) {
			workers.put(i, new InOutWorker(this, i));
		}

		try {
			Process.activate(mainProcess);
		} catch (DiscoException e) {
			logger.error("Failed to activate main simulation process", e);
			throw new SimulationException(e);
		}
	}

	public void stop() {
		assert mainProcess != null;
		for (InOutWorker worker: workers.values()) {
			worker.terminate();
		}
		mainProcess.terminate();
		System.exit(1);//EXTENSION pryc
	}

	public void errorStop(Throwable error) {
		stop();
		error.printStackTrace();
	}

	public ExtendedUnorientedGraph<Point, TrackBlock, Segment> getGraph() {
		return extendedUnorientedGraph;
	}

	public List<InOut> getInOuts() {
		return inouts;
	}

	public boolean isSeparatorInDirection(OrientedPathSeparator separator, Track next, Track previous) {
		assert separator != null;
		final Segment segment = getSegment(separator, next, previous);
		if (segment == null && separator instanceof InOut) return true;
		assert segment != null : separator;
		final Segment direction = separator.direction(); assert direction != null;
		final boolean inDirection = segment == direction;
		if (logger.isDebugEnabled()) {
			logger.debug("isSeparatorInDirection: separator {}, segment={}, direction={}, result={}",
				separator, segment, direction, inDirection);
		}
		return inDirection;
	}


	public Path pathToNextSemaphore(final PathSeparator sep, final TrackSection nxt) {
		if (sep== null || nxt == null) throw new IllegalArgumentException("wrong arguments for aPath finding");
		if (logger.isDebugEnabled()) {
			logger.debug("pathToNextSemaphore: searching path from {} via track section", sep);
		}
		PathSeparator separator = sep;
		TrackSection previous = null;
		TrackSection next = nxt;
		final ArrayPath path = new ArrayPath(this);
		do {
			path.add(separator); path.add(next);
			separator = next.getSecondEnd(separator);
			previous = next;
			next = getNextTrackSection(separator, next);
			if (separator instanceof OrientedPathSeparator) {
				if (isSeparatorInDirection((OrientedPathSeparator) separator, next, previous)) {
					path.add(separator);
					if (logger.isTraceEnabled()) {
						logger.trace("pathToNextSemaphore: found complete path with length {}", path.length());
					}
					return path;
				}
			}

		} while (next != null);
		logger.debug("pathToNextSemaphore: no path found from {}", sep);
		return null;
	}

	public double getCurrentMaxSpeed() {
		return currentMaxSpeed;
	}

	public double getCurrentTrackLength() {
		return currentTrackLength;
	}

	public void setCurrentMaxSpeed(double speed) {
		currentMaxSpeed = speed;
	}

	public void setCurrentTrackLength(double length) {
		currentTrackLength = length;
	}

	public void report(CharSequence report, Object obj, ReportType type) {
		if (!isReporting(type)) return;
		if (!simulationLogger.isInfoEnabled()) return;

		final StringBuilder buf = (report instanceof StringBuilder) ? (StringBuilder) report : new StringBuilder(report);
		try {
			if (obj != null && obj.getClass().getMethod("toString") != Object.class.getMethod("toString")) {
				buf.insert(0, ' ');
				buf.insert(0, obj);
			}
		} catch (Exception e) {
			logger.error("Error generating simulation report for type {}", type, e);
		}
		buf.insert(0, ' ');
		buf.insert(0, jDisco.Process.time());
		simulationLogger.info("{}", buf);
	}

	public void addReportTypes(ReportType... types) {
		if (types == null || types.length <= 0) {
			allowedReportTypes.clear();
		} else {
			Collections.addAll(allowedReportTypes, types);
		}
	}

	public boolean isReporting(ReportType type) {
		return allowedReportTypes.contains(type);
	}

	public void removeReportTypes(ReportType... types) {
		if (types == null || types.length <= 0) return;
		for (ReportType t : types) {
			if (t == null) continue;
			allowedReportTypes.remove(t);
		}
	}

	public String getCurrentNameString() {
		return nameString==null ? randomString() : nameString;
	}

	private final Random random = new Random(0);
	private String randomString() {
		return new String(Character.toChars(65 + random.nextInt(20)));
	}

	public void setCurrentNameString(String name) {
		this.nameString = name;
	}

	public InOutWorker getWorkerFor(InOut inOut) {
		return workers.get(inOut);
	}


	/**
	 * zatim jen pro priklady, kde hlavnim procesem neni generator
	 * @param loop
	 */
	public void setMainProcess(ShuntingLoop loop) {
		mainProcess = loop;
	}
}
