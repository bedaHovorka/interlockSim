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

import jDisco.Random;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
/**
 *
 * Testing Generator
 */
public class Generator extends LoopProcess {//zatim testovaci
	private Random random = new Random();
	private final SimulationContext context;
	private final boolean shuffleInOuts;
	final ArrayList<Train> trains = new ArrayList<Train>();
	private int i = 0;

	static {
		dtMin = 1e-6; dtMax = 1e-3;
		maxRelError = maxAbsError = 1e-2;
	}

	/**
	 *
	 * @param context
	 */
	public Generator(SimulationContext context) {
		this(context, true);
	}

	/**
	 * @param context
	 * @param shuffleInOuts
	 */
	public Generator(SimulationContext context, boolean shuffleInOuts) {
		this.context = context;
		this.shuffleInOuts = shuffleInOuts;
	}

	private Timetable generateRandomTimetable() {
		List<InOut> inOuts = new ArrayList<InOut>(context.getInOuts());
		if (shuffleInOuts) Collections.shuffle(inOuts, random);
		final double timeIn = time() + random.normal(15, 5);
		final double timeOut = timeIn + random.normal(15, 5);

		return new Timetable(inOuts.get(0), inOuts.get(1), new Time(timeIn), new Time(timeOut), 40);
	}

	@Override
	protected void iteration() {
		final Train train = new Train(context, generateRandomTimetable());
		placeTrain(train);
		trains.add(train);
	}

	/**
	 * @param train
	 */
	protected void placeTrain(final Train train) {
		activate(train);
	}

	@Override
	protected void interLoopSleep() {
		hold(random.exp(43));
		i++;
	}

	@Override
	protected void byTerminateAction() {
		for (Train train : trains) {
			while (!train.terminated()) hold(2);
		}
	}
}
