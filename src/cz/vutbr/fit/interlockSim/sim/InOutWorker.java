/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
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
		this.inOut = out;
		this.context = context;
		this.next = context.getNextTrackSection(inOut, null);
	}
	
	private Condition pathFree = new Condition() {
		public boolean test() {
			//EXTENSION az bude Interlocking: co kdyz cesta vubec neexistuje
			path = context.pathToNextSemaphore(inOut, next);//EXTENSION lepe
			try {
				return path != null && path.isFreeFrom(inOut);
			} catch (TrackOperationException e) {
				context.errorStop(e);
				return false;
			}
		}
	};
	
	@Override
	protected void iteration() {
		while (!queqe.empty()) {
			myIdle = false;
			context.report("waiting to free aPath", inOut, ReportType.NODE_EVENTS);
			waitUntil(pathFree);
			final Link first = queqe.first();
			
			try {
				//zarezervovat koleje
				path.setUpPath(inOut);
			} catch (Exception e) {
				context.errorStop(e);
				return;
			}
			context.report("Path reserved for " + first, inOut, ReportType.NODE_EVENTS);
			
			//cekej na odchod vlaku z fronty (bez te anonymni tridy please)
			waitUntil(new Condition() {
				public boolean test() {
					return first != queqe.first();
				}
			});
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
		if (queqe.empty()) {
			train.into(queqe); 
		} else {
			Process.wait(queqe);
		}
		
		if (myIdle) Process.activate(this);
	}
}