/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.vutbr.fit.interlockSim.context.RailwayNetGrid;
import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType;
import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath;
import cz.vutbr.fit.interlockSim.objects.paths.Path;
import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackFacility.State;
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph;
import cz.vutbr.fit.interlockSim.util.Util;

/**
 * Příklad fungování modelu
 * Ovlada sest navestidel a 2 InOuty pomoci predem ulozenych cest
 */
public class ShuntingLoop extends Interlocking {
	private static final Logger logger = LoggerFactory.getLogger(ShuntingLoop.class);
	private static final int MAX_TRAINS = 2; //maximalni pocet odsouhlasených vlaků v systému
	//fronta neodsouhlasenych - za jinych okolnosti seznam ze ktereho si dispecer vybere
	private final Queue<Train> unapprowedTrains = new LinkedList<Train>();
	private final List<Train> approwedTrains = new ArrayList<Train>();
	private final InnerGenerator generator;
	private final HashMap<RailSemaphore, List<Path>> paths = new HashMap<RailSemaphore, List<Path>>();
	private final ArrayList<SimpleTrackBlock> innerTrackBlocks = new ArrayList<SimpleTrackBlock>();
	private final HashMap<SimpleTrackBlock, RailSemaphore> outerTrackblocks = new HashMap<SimpleTrackBlock, RailSemaphore>();
	private final long endTime;

	private class RealTimeSynch extends LoopProcess {
		private double presvihnuto = 0;
		private long beginTime;

		@Override
		protected void startAction() {
			interLoopSleep();
		}

		@Override
		protected void iteration() {
			long endTime = System.currentTimeMillis();
			long sleepTime = 1000 - (endTime - beginTime);
			if (sleepTime > 10) {
				try {
					Thread.sleep(sleepTime);
				} catch (InterruptedException e) {
					assert false : e;
					terminate();
				}
			} else if (sleepTime < 0) {
				presvihnuto = sleepTime / 1000.0;
			}
		}

		@Override
		protected void interLoopSleep() {
			beginTime = System.currentTimeMillis();
			hold(1 + presvihnuto);
			presvihnuto = 0.0;
		}
	}

	private class InnerGenerator extends Generator {
		private InnerGenerator(SimulationContext context) {
			super(context);
		}

		@Override
		protected void placeTrain(Train train) {
			unapprowedTrains.offer(train);
		}
	}

	/**
	 * @param context
	 * @param endTime when simulation schould stop
	 */
	public ShuntingLoop(SimulationContext context, long endTime) {
		super(context);
		this.endTime = endTime;
		generator = new InnerGenerator(context);

		assert context.getGraph().size() > 0;
		//Sit jiz musi byt nactena z vyhybna.xml !!!

		final InOut B = elementAt(InOut.class, 30, 8);
		final InOut A = elementAt(InOut.class, 11, 8);
		final RailSemaphore zA = elementAt("zA", RailSemaphore.class, 14, 8);
		final RailSemaphore doA1 = elementAt("doA1", RailSemaphore.class, 16, 8);
		final RailSemaphore doB1 = elementAt("doB1", RailSemaphore.class, 25, 8);
		final RailSemaphore zB = elementAt("zB",RailSemaphore.class, 27, 8);
		final RailSemaphore doA2 = elementAt("doA2", RailSemaphore.class, 17, 9);
		final RailSemaphore doB2 = elementAt("doB2", RailSemaphore.class, 24, 9);
		final RailSwitch vA = elementAt("vA", RailSwitch.class, 15, 8);
		final RailSwitch vB = elementAt("vB", RailSwitch.class, 26, 8);

		final SimpleTrackBlock k1 = getBlock("k1", doA1, doB1);
		final SimpleTrackBlock k2 = getBlock("k2", doA2, doB2);
		final SimpleTrackBlock kA = getBlock("kA", A, zA);
		final SimpleTrackBlock kB = getBlock("kB", B, zB);

		//usporadani znalostni pro jednoduche rizeni
		constructPath(zA, vA, doA1, k1, doB1);
		constructPath(doA1, vA, zA, kA, A);
		constructPath(zA, vA, doA2, k2, doB2);
		constructPath(doA2, vA, zA, kA, A);
		constructPath(zB, vB, doB1, k1, doA1);
		constructPath(doB1, vB, zB, kB, B);
		constructPath(zB, vB, doB2, k2, doA2);
		constructPath(doB2, vB, zB, kB, B);
		Collections.addAll(innerTrackBlocks, k1, k2);
		outerTrackblocks.put(kB, zB);
		outerTrackblocks.put(kA, zA);
	}

	private ArrayPath constructPath(PathElement...elements) {
		final ArrayPath arrayPath = new ArrayPath(getContext());
		try {
			for (int i = 0; i < elements.length; i++) {
				if (elements[i] instanceof RailSwitch) {
					final RailSemaphore prev = Util.assertInstanceOf(RailSemaphore.class, elements[i-1]);
					final RailSemaphore next = Util.assertInstanceOf(RailSemaphore.class, elements[i+1]);
					arrayPath.addLast(getBlock(switchName(elements[i]), prev, (Cell) elements[i]));
					arrayPath.addLast(elements[i]);
					arrayPath.addLast(getBlock(switchName(elements[i]), next, (Cell) elements[i]));
				} else {
					arrayPath.addLast(elements[i]);
				}
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			assert false : e;
		}
		final RailSemaphore first = Util.assertInstanceOf(RailSemaphore.class, arrayPath.getFirst());
		List<Path> sublist = paths.get(first);
		if (sublist == null) {
			sublist = new ArrayList<Path>(3);
			paths.put(first, sublist);
		}
		sublist.add(arrayPath);
		return arrayPath;
	}

	private String switchName(PathElement el) {
		return Util.assertInstanceOf(RailSwitch.class, el).getName()+"-around";
	}

	private <T extends Cell> T elementAt(Class<T> clazz, int x, int y) {
		final RailwayNetGrid railWayNetGrid = getContext().getRailWayNetGrid();
		return Util.assertInstanceOf(clazz, railWayNetGrid.getCellAt(x, y));
	}

	private <T extends NodeCell> T elementAt(String name, Class<T> clazz, int x, int y) {
		final T elementAt = elementAt(clazz, x, y);
		elementAt.setName(name);
		return elementAt;
	}

	private SimpleTrackBlock getBlock(String name, Cell cell1, Cell cell2) {
		final RailwayNetGrid railWayNetGrid = getContext().getRailWayNetGrid();
		final ExtendedUnorientedGraph<Point, TrackBlock, Segment> graph = getContext().getGraph();
		final SimpleTrackBlock assertInstanceOf = Util.assertInstanceOf(SimpleTrackBlock.class, graph.get(railWayNetGrid.getLocation(cell1), railWayNetGrid.getLocation(cell2)));
		assertInstanceOf.setName(name);
		return assertInstanceOf;
	}

	@Override
	protected void startAction() {
		getContext().addReportTypes(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS, ReportType.NODE_EVENTS);
		//activate(new RealTimeSynch());
		activate(generator);
	}

	@Override
	protected void iteration() {
		//stare vlaky
		for (Iterator<Train> iter = approwedTrains.iterator(); iter.hasNext();) {
			Train element = iter.next();
			if (element.terminated()) iter.remove();
		}
		//nove vlaky a inouty
		approveTrains();
		hold(1);
		for (SimpleTrackBlock  block : innerTrackBlocks) checkBothEnds(block);
		for (Entry<SimpleTrackBlock, RailSemaphore> e : outerTrackblocks.entrySet()) checkOneEnd(e.getKey(), e.getValue());
	}

	private void checkBothEnds(SimpleTrackBlock block) {
		for (NodeCell sep : block.ends()) {
			if (checkOneEnd(block, Util.assertInstanceOf(RailSemaphore.class, sep))) return;
		}
	}

	private boolean trySetupPath(Path path) {
		try {
			final PathSeparator from = path.getFirst();
			if (!path.isFreeFrom(from)) {
				logger.debug("Path not free from separator: {}", from);
				return false;
			}
			logger.debug("Setting up path from separator: {}", from);
			path.setUpPath(from);
			return true;
		} catch (TrackOperationException e) {
			assert false : e;
			logger.debug("Exception during path setup: {}", e.getMessage());
			return false;
		}
	}

	private boolean trySetupPaths(RailSemaphore sem) {
		logger.debug("Attempting to setup paths from semaphore: {}", sem.getName());
		for (Path path : paths.get(sem)) {
			//zkusit postavit cestu
			try {
				if (path.isSetUpPath(sem) || trySetupPath(path)) {
					logger.debug("Path setup successful from semaphore: {}", sem.getName());
					return true;
				}
			} catch (TrackOperationException e) {
				assert false : e;
				logger.debug("Exception in path setup attempt: {}", e.getMessage());
			}
		}
		logger.debug("All path setup attempts failed from semaphore: {}", sem.getName());
		return false;
	}

	private boolean checkOneEnd(SimpleTrackBlock block, RailSemaphore to) {
//		 je v bloku vlak?
		if (block.getState() == State.FREE) return false;
		if (block.getState() == State.OCCUPIED) {
			logger.debug("Block occupied, checking if next semaphore is: {}", to.getName());
			if (block.getTrackOccupant().nextSemaphore() != to) return false;
			return trySetupPaths(to);
		} else if (block.getState() == State.RESERVED) {
			logger.debug("Block reserved, checking path setup for semaphore: {}", to.getName());
			if (block.isSetUpPath(block.getSecondEnd(to))) {
				return trySetupPaths(to);
			}
		}
		return false;
	}

	private void approveTrains() {
		while (approwedTrains.size() < MAX_TRAINS && unapprowedTrains.size() > 0) {
			final Train poll = unapprowedTrains.poll();
			logger.debug("Approving train: {} (approved: {}/{} max)", poll, approwedTrains.size() + 1, MAX_TRAINS);
			approwedTrains.add(poll);
			activate(poll);
		}
	}

	@Override
	protected void interLoopSleep() {
		if (time() >= endTime) {
			generator.terminate();
			terminate();
			return;
		}
		hold(1);
	}
}
