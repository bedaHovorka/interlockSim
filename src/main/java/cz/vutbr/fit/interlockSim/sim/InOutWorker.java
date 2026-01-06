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

import jDisco.Condition;
import jDisco.Head;
import jDisco.Link;
import jDisco.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.paths.Path;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection;

/**
 * Behaviour of {@link InOut}
 *
 */
public class InOutWorker extends LoopProcess {
	private static final Logger logger = LoggerFactory.getLogger(InOutWorker.class);

	private final Head queqe = new Head();
	private final InOut inOut;
	private boolean myIdle = true;//neobsluhuje vlak
	private final TrackSection next;
	private final SimulationContext context;
	private Path path; //	cesta k naskedujicimu semaforu - pokud existuje

	/**
	 * @param context
	 * @param out
	 */
	public InOutWorker(SimulationContext context, InOut out) {
		if (context == null) {
			throw new NullPointerException("context must not be null");
		}
		if (out == null) {
			throw new NullPointerException("out must not be null");
		}
		this.inOut = out;
		this.context = context;
		this.next = context.getNextTrackSection(inOut, null);
	}

	private Condition pathFree = new Condition() {
		public boolean test() {
			//EXTENSION az bude Interlocking: co kdyz cesta vubec neexistuje
			path = context.pathToNextSemaphore(inOut, next);//EXTENSION lepe
			try {
				final boolean pathExists = path != null;
				final boolean isFree = pathExists && path.isFreeFrom(inOut);

				if (logger.isDebugEnabled()) {
					if (!pathExists) {
						logger.debug("{} APPROVAL_CHECK: InOut {} - path does not exist",
							jDisco.Process.time(), inOut.getName());
					} else if (!isFree) {
						logger.debug("{} APPROVAL_CHECK: InOut {} - path not free, length={}",
							jDisco.Process.time(), inOut.getName(), path.length());
					}
				}
				return isFree;
			} catch (TrackOperationException e) {
				logger.error("{} APPROVAL_ERROR: InOut {} - path check failed: {}",
					jDisco.Process.time(), inOut.getName(), e.getMessage());
				context.errorStop(e);
				return false;
			}
		}
	};

	@Override
	protected void iteration() {
		while (!queqe.empty()) {
			myIdle = false;
			logger.debug("InOutWorker {} queue non-empty, processing train", inOut.getName());
			context.report("waiting to free aPath", inOut, ReportType.NODE_EVENTS);
			waitUntil(pathFree);
			final Link first = queqe.first();
			logger.debug("InOutWorker {} path is now free, reserving for train", inOut.getName());

			try {
				//zarezervovat koleje
				path.setUpPath(inOut);
				if (logger.isInfoEnabled()) {
					logger.info("{} APPROVAL_GRANTED: InOut {} - path reserved for {}, length={}",
						jDisco.Process.time(), inOut.getName(), first, path.length());
				}
			} catch (Exception e) {
				if (logger.isWarnEnabled()) {
					logger.warn("{} APPROVAL_DENIED: InOut {} - path setup failed for {}: {}",
						jDisco.Process.time(), inOut.getName(), first, e.getMessage());
				}
				logger.debug("InOutWorker {} path setup failed: {}", inOut.getName(), e.getMessage());
				context.errorStop(e);
				return;
			}
			context.report("Path reserved for " + first, inOut, ReportType.NODE_EVENTS);

			//cekej na odchod vlaku z fronty (bez te anonymni tridy please)
			logger.debug("InOutWorker {} waiting for train {} to leave queue", inOut.getName(), first);
			waitUntil(() -> first != queqe.first());
			logger.debug("InOutWorker {} train left queue", inOut.getName());
		}
		myIdle = true;
	}

	/**
	 * @return input queque
	 */
	public Head getQueqe() {
		return queqe;
	}

	/**
	 * in Inout is new Train - budicek procesu
	 * @param train
	 */
	public void enterTrain(Train train) {
		logger.debug("InOutWorker {} entering train {}, queue empty: {}", inOut.getName(), train, queqe.empty());
		if (queqe.empty()) {
			train.into(queqe);
		} else {
			Process.wait(queqe);
		}

		if (myIdle) {
			logger.debug("InOutWorker {} was idle, activating to process train {}", inOut.getName(), train);
			Process.activate(this);
		}
	}
}
