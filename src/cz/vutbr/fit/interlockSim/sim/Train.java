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
import jDisco.Continuous;
import jDisco.Process;
import jDisco.Reporter;
import jDisco.Variable;
import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore.Signal;
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator;
import cz.vutbr.fit.interlockSim.objects.paths.Path;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackOccupant;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection;

/**
 * Train Process
 *
 */
public class Train extends Process implements TrackOccupant {
	private final Reporter r = new Reporter() {//nesmi byt static!!!
		private boolean started = false;
		@Override
		protected void actions() {
			if (!started || !context.isReporting(ReportType.TRAIN_CONTINUOUS)) return; //opti-hack
			final StringBuilder builder = new StringBuilder();
			builder.append(getAccelaration()).append(' ');
			builder.append(getVelocity()).append(' ');
			builder.append(front.getTotalDistance()).append(' ');
			//builder.append(tail.getTotalDistance()).append(' ');
			builder.append(front.getSection()).append(' ');
			builder.append(tail.getSection()).append(' ');
			final double distanceToSemaphore = distanceToSemaphore();
			builder.append(distanceToSemaphore > 0 ? distanceToSemaphore : 0);
			context.report(builder, Train.this, ReportType.TRAIN_CONTINUOUS);
		}

		@Override
		public Reporter start() {
			started = true;
			return super.start();
		}

		@Override
		public void stop() {
			started = false;
			super.stop();
		}
	}.setFrequency(1);

	//EXTENSION musi bud aspon umet prohodit zacatek a konec
	//nebo je udalosti oba dva zrusit a dalsi udalosti obnovit, s tim ze vlak stoji na miste
	//tj. "odpojeni" a "pripojeni" lokomotivy

	private abstract class Site extends Process { //lepsi nazev?
		private final Variable position = new Variable(0);
		private final SimpleIntegration pv = new SimpleIntegration(position, velocity);
		private double totalLenghtOfPreviousBlocks = 0;
		private TrackSection next = null;
		private TrackSection current = null;
		private boolean onNext = false;

		final Condition terminated = new Condition() {
			public boolean test() {
				return terminated();
			}
		};

		@Override
		protected final void actions() {
			PathSeparator where = timetable.getIn(); assert where != null;
			//out se muze rovnat in => bude vyreseno "prepojenim lokomotivy"

			while (true) {
				next = context.getNextTrackSection(where, current);
				if (next == null) {
					if (where instanceof InOut) break;
					context.stop();
					passivate();
				}
				final double nextLength = next.length();
				separatorAction(where, current, next);

				onNext = true;
				assert position.isActive() && pv.isActive();
				waitUntil(new Condition() {
					public boolean test() {
						//dtmin - horni odhad zmeny pri poslednim kroku numericke metody behem dobrzdovani k uzlu
						return position.state + dtMin >= nextLength;
					}
				});

				position.state -= nextLength;
				totalLenghtOfPreviousBlocks += nextLength;
				where = next.getSecondEnd(where); assert where != null;
				current = next;
				onNext = false;
			}

			stop();
			separatorAction(where, current, null);
		}

		/**
		 * Action at aPath separator
		 * @param where
		 * @param current
		 * @param next
		 */
		public abstract void separatorAction(PathSeparator where, TrackSection current, TrackSection next);

		@Override
		public Site start() {
			position.start();
			pv.start();
			return this;
		}

		@Override
		public void stop() {
			position.stop();
			pv.stop();
		}

		/**
		 * @return distance
		 */
		public double distanceToPathSeparator() {
			return next==null ? 0 : next.length() - position.state;
		}

		/**
		 * @return getter
		 */
		public double getPosition() {
			return position.state;
		}

		/**
		 * @return "to co cast vlaku urazila uvnitr modelu"
		 */
		public double getTotalDistance() {
			return totalLenghtOfPreviousBlocks + position.state;
		}

		protected TrackSection getSection() {
			return onNext ? next : current;
		}
	}

	private class Front extends Site {
		private void semaphoreAction(final RailSemaphore semaphore, final PathSeparator separator, final TrackSection current, final TrackSection next) {
			assert context.isSeparatorInDirection((OrientedPathSeparator)separator, next, current) : semaphore;
			assert semaphore.getSignal() != null;
			final Path path = context.pathToNextSemaphore(separator, next);

			// EXTENSION stanice

			if (semaphore.getSignal() == RailSemaphore.Signal.STOP) {
				assert getVelocity() <= maxAbsError: "prujezd na cervenou";
				fireStop();
				context.report(semaphore.getSignal().toString(), Train.this, ReportType.TRAIN_EVENTS);

				//freePath(separator, next); //vlak si sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
				waitUntil(allowingSignal(semaphore));
				context.report("OK " + semaphore.getSignal(), Train.this, ReportType.TRAIN_EVENTS);
				fireStart(semaphore, context.pathToNextSemaphore(separator, next)); // znovu najit
			} else if (semaphore.getSignal().isAllowing() && velocity.state <= maxAbsError) {
				fireStart(semaphore, path);
			} else {
				accelerateToSignal(semaphore, path);
			}
			hold(1);
			semaphore.setSignal(RailSemaphore.Signal.STOP);
		}

		//pro ucely ladeni - moznost ze si vlak sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
//		private void freePath(final PathSeparator separator, final TrackSection next) {
//			if (separator instanceof InOut) return;
//			try {
//				context.pathToNextSemaphore(separator, next).setUpPath(separator);
//			} catch (TrackOperationException e1) {
//				context.errorStop(e1);
//				e1.printStackTrace();
//			}
//			Process.activate(new Process() {
//
//				@Override
//				protected void actions() {
//					waitUntil(new Condition() {
//						Path aPath;
//
//						public boolean test() {
//							aPath = context.pathToNextSemaphore(separator, next);
//							try {
//								final boolean b = aPath != null && aPath.isFreeFrom(separator);
//								if (b == true) aPath.setUpPath(separator);
//								return b;
//							} catch (TrackOperationException e) {
//								context.errorStop(e);
//								return false;
//							}
//						}
//
//					});
//
//				}
//
//			});
//		}

		private Condition allowingSignal(final RailSemaphore semaphore) {
			return new Condition() {
				public boolean test() {
					final boolean allowing = semaphore.getSignal().isAllowing();
					return allowing;
				}
			};
		}

		private void fireStop() {
			assert getVelocity() <= maxAbsError;
			front.stop();
			tail.stop();
			Train.this.stop();
			velocity.state = 0;
			r.stop();
		}

		private void fireStart(final RailSemaphore semaphore, final Path path) {
			accelerateToSignal(semaphore, path);
			Train.this.start();
			front.start();
			tail.start();
			r.start();
		}

		private void accelerateToSignal(final RailSemaphore semaphore, final Path path) {
			assert path != null;
			final Signal thisSignal = semaphore.getSignal(); assert thisSignal.isAllowing() : thisSignal;
			final RailSemaphore nextSemaphore = path.getLastPathSemaphore();
			final Signal nextSignal = nextSemaphore.getSignal();
			pathToSemaphore = path;

			final double min = Math.min(path.maxSpeed(path.getFirst()), thisSignal.allowedSpeed());
			if (nextSignal.isAllowing()) {
				motor.accelerateTo(Math.min(nextSignal.allowedSpeed(), min));
			} else {
				motor.onWarning(min);
			}
		}

		@Override
		public void separatorAction(PathSeparator where, TrackSection current, TrackSection next) {

			if (where instanceof RailSemaphore && context.isSeparatorInDirection((RailSemaphore) where, next, current)) {
				RailSemaphore semaphore = (RailSemaphore) where;
				semaphoreAction(semaphore, semaphore, current, next);
			} else if (where == timetable.getIn()) {
				assert getAccelaration() == 0 && getVelocity() == 0; //jestli sou nastaveny poc. podminky, muzu ==
				semaphoreAction(((InOut) where).getInSemaphore(), where, current, next);
			} else {
				pathToSemaphore.removeFirst();
				pathToSemaphore.removeFirst();
			}
			assert pathToSemaphore.getFirst() == where: pathToSemaphore;
			if (next != null) next.enter(Train.this);
		}


	}

	private class Tail extends Site {
		private boolean fromHome = false;
		@Override
		public void separatorAction(PathSeparator where, TrackSection current, TrackSection next) {
			if (where == timetable.getIn()) {
				fromHome = true;
				start();
			}

			if (current != null) current.leave(Train.this);
			if (next == null && where != timetable.getOut()) context.report("ends in wrong out", Train.this, ReportType.TRAIN_EVENTS);
		}

		@Override
		public Site start() {
			return (fromHome) ? super.start() : this;
		}

	}

	private class LenghtChecker extends AssertionContinuous {
		@Override
		public boolean check(){
			return Math.abs(front.getTotalDistance() - tail.getTotalDistance() - getLength()) <= maxAbsError;
		}

		@Override
		public StringBuilder report(final StringBuilder reportObj) {
			assert reportObj != null;
			reportObj.append(front.getTotalDistance()).append(' ').append(tail.getTotalDistance());
			return reportObj.append(' ').append(getLength());
		}
	}

	private enum AccelerationStopTest  {
		/**
		 *
		 */
		ACCELERATION_ENDED(false),
		/**
		 *
		 */
		TO_HALF_SPEED(false) {
			@Override
			public boolean condition(double targetSpeed, double velocity) {
				return targetSpeed <= 2*velocity;
			}
		},
		/**
		 *
		 */
		DECELERATION_ENDED(true);

		private final boolean decelarate;

		protected boolean isDecelarate() {
			return decelarate;
		}

		private AccelerationStopTest(final boolean decelarate) {
			this.decelarate = decelarate;
		}

		boolean condition(double targetSpeed, double velocity) {
			return (isDecelarate()) ? targetSpeed >= velocity : targetSpeed <= velocity;
		}
	}

	private class Motor extends LoopProcess {
		private static final int MAXIMAL_ACCELERATION = 4;
		private static final int MINIMAL_DECELERATION = -3;
		private AccelerationStopCondition currentCondition;
		private double targetSpeed;
		private boolean accelerate = false;

		private class AccelerationStopCondition implements Condition {
			private final AccelerationStopTest stopTest;

			AccelerationStopCondition(final AccelerationStopTest stopTest) {
				super();
				this.stopTest = stopTest;
			}

			public boolean test() {
				return !accelerate || stopTest.condition(targetSpeed, getVelocity());
			}

			protected AccelerationStopTest getStopTest() {
				return stopTest;
			}
		}

		@Override
		protected void iteration() {
			assert currentCondition != null;
			accelerate = true;
			start();
			waitUntil(currentCondition);

			if (accelerate && currentCondition.getStopTest() == AccelerationStopTest.TO_HALF_SPEED) {
				targetSpeed = 0;
				waitUntil(new AccelerationStopCondition(AccelerationStopTest.DECELERATION_ENDED));
			}

			accelerate = false;
			stop();
			accelaration.state = 0;
		}

		private void privateAccelerateTo(double speed, AccelerationStopTest test) {
			assert speed >= 0;
			targetSpeed = speed;
			currentCondition = new AccelerationStopCondition(test);
			cancelAccelerating();
			activate(this);
		}

		/**
		 * change speed
		 * @param speed
		 */
		public void accelerateTo(final double speed) {
			context.report("in on warning", Train.this, ReportType._DEBUG);
			privateAccelerateTo(speed, speed > getVelocity() ? AccelerationStopTest.ACCELERATION_ENDED : AccelerationStopTest.DECELERATION_ENDED);
		}

		/**
		 * special behaviour
		 * @param normalSpeed
		 */
		public void onWarning(final double normalSpeed) {
			context.report("in on warning " + normalSpeed, Train.this, ReportType._DEBUG);

			assert getVelocity() <= maxAbsError;
			privateAccelerateTo(normalSpeed, AccelerationStopTest.TO_HALF_SPEED);
		}

		/**
		 *
		 */
		public void cancelAccelerating() {
			if (accelerate) {
				accelerate = false;
				activate(this);
			}
		}

		@Override
		public Continuous start() {
			return (accelerate) ? super.start() : this;
		}

		@Override
		protected void derivatives() {
			//minmax zpomaleni
			final double s = distanceToSemaphore();
			if (s <= 0) {
				accelerate = false;
				return;
			}
			if (velocity.state <= 0) velocity.state = 0;

			final double a = ((targetSpeed - velocity.state)*(targetSpeed + velocity.state)) / (2*s);
			accelaration.state = currentCondition.getStopTest().isDecelarate() ?
								Math.max(a, MINIMAL_DECELERATION) :
								Math.min(a, MAXIMAL_ACCELERATION);
		}
	}


	private final Variable accelaration = new Variable(0);
	private final Variable velocity = new Variable(0);
	private final SimpleIntegration va = new SimpleIntegration(velocity, accelaration);
	private final Front front = new Front();
	private final Tail tail = new Tail();
	private final Motor motor = new Motor();
	private final Timetable timetable;
	private final SimulationContext context;
	private Path pathToSemaphore;
	private final String trainPrefix;

	private static int count = 0;
	private final int number;

	private double length;
	private AssertionContinuous ap = new LenghtChecker();

	/**
	 * Create new train
	 * @param context
	 * @param timetable
	 */
	public Train(SimulationContext context, Timetable timetable) {
		this.context = context;
		this.timetable = timetable;
		this.length = timetable.getLength();
		number = ++count;
		trainPrefix = "Train #"+number;
	}

	public double distanceToSemaphore() {
		return pathToSemaphore==null ? 0 : pathToSemaphore.length() - front.getPosition();
	}

	@Override
	protected void actions() {//spusten odsouhlasenim
		//zarazeni do fronty vstupniho bodu (simulace systemu sousedni stanice)
		final InOut inout = timetable.getIn();
		final InOutWorker worker = context.getWorkerFor(inout);
		worker.enterTrain(this);
		context.report("approved "+inout.getName()+"->"+timetable.getOut().getName(), this, ReportType.TRAIN_EVENTS);

		activate(front);

		waitUntil(new Condition() {
			public boolean test() {
				return front.getTotalDistance() >= getLength();
			}
		});
		activate(tail);

		out(); activate((Train) worker.getQueqe().first());
		ap.start();

		waitUntil(front.terminated);
		ap.stop();
		//predkem v systemu sousedni stanice

		waitUntil(tail.terminated);
		r.setFrequency(Double.POSITIVE_INFINITY);
		r.stop();
		stop();
		motor.terminate();
		//ukoncovaci..
		context.report("ends", this, ReportType.TRAIN_EVENTS);
	}

	/**
	 * @return current acceleration of train
	 */
	public double getAccelaration() {
		return accelaration.state;
	}

	/**
	 * @return current speed of train
	 */
	public double getVelocity() {
		return velocity.state;
	}

	/**
	 * @return length of train
	 */
	public double getLength() {
		return length;//pozdeji soucet vagonu
	}

	public OrientedPathSeparator nextSemaphore() {
		return pathToSemaphore.getLast();
	}

	@Override
	public Train start() {
		accelaration.start();
		velocity.start();
		va.start();
		return this;
	}

	@Override
	public void stop() {
		accelaration.stop();
		velocity.stop();
		va.stop();
		velocity.state = 0;
		velocity.rate = accelaration.rate = 0;
		accelaration.state = 0;
	}

	@Override
	public String toString() {
		return trainPrefix;
	}
}
