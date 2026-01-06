/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test Utility: Mock Simulation Context
	Test-friendly simulation context implementation

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test infrastructure: 2025
*/

package cz.vutbr.fit.interlockSim.testutil;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import cz.vutbr.fit.interlockSim.context.DefaultContext;
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid;
import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.context.EmptyContextException;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator;
import cz.vutbr.fit.interlockSim.objects.paths.Path;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.objects.tracks.Track;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection;
import cz.vutbr.fit.interlockSim.sim.InOutWorker;
import cz.vutbr.fit.interlockSim.sim.SimulationException;
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph;
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory;

/**
 * Mock implementation of {@link SimulationContext} for testing simulation components
 * without requiring jDisco framework initialization.
 *
 * <p>This mock provides controllable simulation time and test-friendly implementations
 * of simulation methods. It extends the test context builder functionality to support
 * simulation-specific operations.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * MockSimulationContext mockContext = new MockSimulationContext();
 * mockContext.advanceTime(1.0); // Advance simulation time by 1 second
 * double time = mockContext.time(); // Returns 1.0
 *
 * mockContext.addReportTypes(ReportType.TRAIN_EVENTS);
 * mockContext.report("Train event", train, ReportType.TRAIN_EVENTS);
 * }</pre>
 *
 * @see SimulationContext
 * @see TestContextBuilder
 */
public class MockSimulationContext implements SimulationContext {

	private final DefaultContext delegate;
	private double currentTime = 0.0;
	private final Map<InOut, InOutWorker> workers = new HashMap<>();
	private final Collection<ReportType> enabledReports = new ArrayList<>();
	private boolean stopped = false;

	/**
	 * Creates a new mock simulation context with empty 100x100 grid.
	 */
	public MockSimulationContext() {
		this.delegate = XMLContextFactory.getInstance().createEmptyContext();
		// Enable all standard reports by default
		for (ReportType type : ReportType.ALL) {
			enabledReports.add(type);
		}
	}

	/**
	 * Creates a mock simulation context wrapping an existing DefaultContext.
	 *
	 * @param context existing context to wrap
	 */
	public MockSimulationContext(DefaultContext context) {
		this.delegate = context;
		// Enable all standard reports by default
		for (ReportType type : ReportType.ALL) {
			enabledReports.add(type);
		}
	}

	/**
	 * Advances the simulation time by the specified delta.
	 *
	 * @param delta time increment in seconds
	 */
	public void advanceTime(double delta) {
		this.currentTime += delta;
	}

	/**
	 * Sets the simulation time to a specific value.
	 *
	 * @param time new simulation time in seconds
	 */
	public void setTime(double time) {
		this.currentTime = time;
	}

	/**
	 * Returns the current simulation time.
	 *
	 * @return current time in seconds
	 */
	public double time() {
		return currentTime;
	}

	/**
	 * Registers an InOutWorker for the specified InOut point.
	 *
	 * @param inOut the InOut point
	 * @param worker the worker to register
	 */
	public void registerWorker(InOut inOut, InOutWorker worker) {
		workers.put(inOut, worker);
	}

	@Override
	public InOutWorker getWorkerFor(InOut inOut) {
		return workers.get(inOut);
	}

	@Override
	public Collection<InOut> getInOuts() {
		Collection<InOut> inOuts = new ArrayList<>();
		RailwayNetGrid grid = delegate.getRailWayNetGrid();
		for (Point p : delegate.getGraph().nodeSet()) {
			Object cell = grid.get(p);
			if (cell instanceof InOut) {
				inOuts.add((InOut) cell);
			}
		}
		return inOuts;
	}

	@Override
	public void run() throws EmptyContextException, SimulationException {
		// Mock implementation - does not actually run simulation
		stopped = false;
	}

	@Override
	public void stop() {
		stopped = true;
	}

	@Override
	public void errorStop(Throwable error) {
		stopped = true;
		throw new RuntimeException("Simulation error", error);
	}

	/**
	 * Returns whether the simulation has been stopped.
	 *
	 * @return true if stopped
	 */
	public boolean isStopped() {
		return stopped;
	}

	@Override
	public void report(CharSequence report, Object obj, ReportType type) {
		if (isReporting(type)) {
			System.out.println(String.format("[%s] %.2f: %s", type, currentTime, report));
		}
	}

	@Override
	public void addReportTypes(ReportType... types) {
		for (ReportType type : types) {
			if (!enabledReports.contains(type)) {
				enabledReports.add(type);
			}
		}
	}

	@Override
	public void removeReportTypes(ReportType... types) {
		for (ReportType type : types) {
			enabledReports.remove(type);
		}
	}

	@Override
	public boolean isReporting(ReportType type) {
		return enabledReports.contains(type);
	}

	// Delegate methods to wrapped DefaultContext

	@Override
	public TrackSection getNextTrackSection(PathSeparator separator, TrackSection current) {
		// Simplified implementation for testing
		return null;
	}

	@Override
	public Path pathToNextSemaphore(PathSeparator separator, TrackSection next) {
		// Simplified implementation for testing
		return null;
	}

	@Override
	public TrackBlock getNextTrackBlock(NodeCell nodeCell, TrackBlock current) {
		// Simplified implementation for testing
		return null;
	}

	@Override
	public Segment getSegment(PathSeparator separator, Track track) {
		// Simplified implementation for testing
		return Segment.A;
	}

	@Override
	public Segment getSegment(PathSeparator separator, Track track, Track secondEndTrack) {
		// Simplified implementation for testing
		return Segment.A;
	}

	@Override
	public boolean isSeparatorInDirection(OrientedPathSeparator separator, Track next, Track previous) {
		// Simplified implementation for testing
		return true;
	}

	@Override
	public RailwayNetGrid getRailWayNetGrid() {
		return delegate.getRailWayNetGrid();
	}

	@Override
	public ExtendedUnorientedGraph<Point, TrackBlock, Segment> getGraph() {
		return delegate.getGraph();
	}

	@Override
	public void addPropertyChangeListener(java.beans.PropertyChangeListener listener) {
		delegate.addPropertyChangeListener(listener);
	}

	@Override
	public void removePropertyChangeListener(java.beans.PropertyChangeListener listener) {
		delegate.removePropertyChangeListener(listener);
	}

	/**
	 * Returns the underlying DefaultContext for advanced operations.
	 *
	 * @return delegate context
	 */
	public DefaultContext getDelegate() {
		return delegate;
	}
}
