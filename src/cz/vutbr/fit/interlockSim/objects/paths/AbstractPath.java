/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.tracks.AbstractTrack;
import cz.vutbr.fit.interlockSim.objects.tracks.Track;
import cz.vutbr.fit.interlockSim.sim.PathSeparatorChangeException;
import cz.vutbr.fit.interlockSim.sim.TrackOperationException;
import cz.vutbr.fit.interlockSim.util.Util;

/**
 * Base implemetation of {@link Path}
 */
public abstract class AbstractPath extends AbstractTrack implements Path {
	private static final String IS_FREE_FROM = "isFreeFrom";
	private static final String SET_UP_PATH = "setUpPath";
	private static final String CANCEL_PATH_SETUP = "cancelPathSetup";
	private static final String IS_SET_UP_PATH = "isSetUpPath";
	private static final Map<String, Method> trackMethods =  new HashMap<String, Method>();
	private static final Map<Class<?>, Class<?>[]> parameterTypes =  new HashMap<Class<?>, Class<?>[]>();
	private final SimulationContext context;

	static {
		try {
			parameterTypes.put(Track.class, new Class[]{PathSeparator.class});
			final ArrayList<String> nameList = new ArrayList<String>();
			Collections.addAll(nameList, CANCEL_PATH_SETUP, SET_UP_PATH, IS_FREE_FROM, IS_SET_UP_PATH);
			fillMethodMap(trackMethods, Track.class, nameList);
		} catch (Exception e) {
			assert false : e;
			e.printStackTrace();
		}
	}

	protected AbstractPath(final SimulationContext context) {
		super();
		this.context = context;
	}

	private static void fillMethodMap(Map<String, Method> map, Class<?> clazz, List<String> methodNames) throws Exception {
		for (String name : methodNames) {
			final Method method = clazz.getMethod(name, parameterTypes.get(clazz));
			map.put(name, method);
		}
	}

	public RailSemaphore getLastPathSemaphore() {
		final PathElement last = getLast();
		if (last instanceof RailSemaphore) return (RailSemaphore) last;
		return Util.assertInstanceOf(InOut.class, last).getOutSemaphore();
	}

	@Override
	public double maxSpeed(PathSeparator sep) {
		PathSeparator prevSep = sep;
		//Track prevTrack = null;
		double min = sep.allowedSpeed();

		for (Iterator<PathElement> it = getIterator(sep); it.hasNext();) {
			final PathElement e = it.next();

			if (e == getLast() || e == getFirst()) {
				//EMPTY
			} else {
				final double es;
				if (e instanceof Track) {
					final Track track = (Track) e;
					es = track.maxSpeed(prevSep);
					//prevTrack = track;
				} else {
					final PathSeparator ps = Util.assertInstanceOf(PathSeparator.class, e);
					prevSep = ps;
					continue; //DEBUG
//					if (ps instanceof OrientedPathSeparator &&
//							!context.isSeparatorInDirection((OrientedPathSeparator)sep, null, prevTrack)) continue;
//					es = ps.allowedSpeed();
				}
				min = es < min ? es : min;
			}
		}
		return min;
	}

	@Override
	public double length() {
		double sum = 0;
		for (PathElement e : this) {
			if (e instanceof Track) {
				sum += ((Track) e).length();
			}
		}
		return sum;
	}

	public PathSeparator[] ends() {
		return new PathSeparator[]{getFirst(), getLast()};
	}

	public boolean isFreeFrom(PathSeparator sep) throws TrackOperationException {
		return pathIterating(sep);
	}

	public boolean isSetUpPath(PathSeparator sep) throws TrackOperationException {
		return pathIterating(sep);
	}

	@Override
	public void setUpPath(PathSeparator sep) throws TrackOperationException {
		pathIterating(sep);
	}

	public void cancelPathSetup(PathSeparator sep) throws TrackOperationException {
		pathIterating(sep);
	}

	private boolean pathIterating(PathSeparator sep) throws TrackOperationException {
		try {
			// lepsi nez 10x copypastovat kod z cyklem a vymenit jen volani metody
			final StackTraceElement e = Thread.currentThread().getStackTrace()[2];
			final String methodName = e.getMethodName();
			final Method method = trackMethods.get(methodName);
			Track previous = null;

			for (Iterator<PathElement> iterator = getIterator(sep); iterator.hasNext();) {
				final PathSeparator separator = Util.assertInstanceOf(PathSeparator.class, iterator.next());
				if (!iterator.hasNext()) break; // posledni prvek je semafor a separatorSetting ho nenastavuje
				final Track nextTrack = Util.assertInstanceOf(Track.class, iterator.next());

				if (!separatorSetting(methodName, separator, previous, nextTrack)) return false;

				final Object invoke = method.invoke(nextTrack, new Object[]{separator});
				if (Boolean.FALSE.equals(invoke)) return false;
				assert invoke == null || Boolean.TRUE.equals(invoke) : invoke;
				previous = nextTrack;
			}
			if (method == trackMethods.get(SET_UP_PATH)) setUpSemaphores(sep);
			return true;
		} catch (InvocationTargetException e) {
			final Throwable cause = e.getCause();
			throw cause instanceof TrackOperationException ? (TrackOperationException) cause : new TrackOperationException(cause, this);
		} catch (Exception e) {
			throw new TrackOperationException(e, this);
		}
	}

	private boolean separatorSetting(final String methodName, final PathSeparator separator, final Track previous, final Track next) throws PathSeparatorChangeException {
		assert methodName != null && next != null;
		final Segment from = context.getSegment(separator, previous, next);
		final Segment to = context.getSegment(separator, next, previous);
		assert !Segment.conflict(from, to) : from + " " + to;

		if (methodName.equals(IS_SET_UP_PATH)) {
			return separator.getFollowingSegment(from) == to;
		} else if (methodName.equals(CANCEL_PATH_SETUP)) {
			separator.cancelPathSetup(from, to);
		} else if (methodName.equals(SET_UP_PATH)) {
			if (!(separator instanceof RailSemaphore)) separator.setUpPath(from, to, separator.allowedSpeed());
			assert separator.getFollowingSegment(from) == to : separator;
		} else if (methodName.equals(IS_FREE_FROM)) {
			//EMPTY
		} else {
			throw new IllegalArgumentException("wrong method name");
		}
		return true;
	}

	private void setUpSemaphores(PathSeparator sep) throws PathSeparatorChangeException {
		//ukolem je natavit zpetnym pruchodem rychlosti semaforu podle vyhybek
		RailSwitch previousSwitch = null;
		Track previousTrack = null;
		for (final Iterator<PathElement> iterator = getIterator(getSecondEnd(sep)); iterator.hasNext();) {
			PathElement element = iterator.next();
			if (element instanceof RailSwitch) {
				previousSwitch = (RailSwitch) element;
			} else if (element instanceof OrientedPathSeparator) {
				if (previousTrack == null) continue;
				final OrientedPathSeparator semaphore = (OrientedPathSeparator) element;
				if (context.isSeparatorInDirection(semaphore, previousTrack, null)) {
					final double speed = (previousSwitch == null) ? PathElement.ABSOLUTE_MAX_SPEED : previousSwitch.allowedSpeed();
					final Segment segment = context.getSegment(semaphore, null, previousTrack);
					final Segment segment2 = context.getSegment(semaphore, previousTrack, null);
					semaphore.setUpPath(segment, segment2, speed);
					previousSwitch = null;
				}
			} else {
				previousTrack = Util.assertInstanceOf(Track.class, element);
			}
		}
		context.report("", this, ReportType.PATH_SETTING);
		assert maxSpeed(sep) >= PathElement.MINIMAL_MAX_SPEED : maxSpeed(sep);
	}

	protected Iterator<PathElement> getIterator(PathSeparator sep) {
		if (!isEnd(sep)) throw new IllegalArgumentException("Is not end of abstrPath");
		if (sep == getFirst()) return iterator();
		assert sep == getLast();
		return descendingIterator();
	}

	public boolean equalsWithElements(Path path) {
		if (path == null) return false;
		if (path == this) return true;
		if (size() != path.size()) return false;
		if (size() == 0) return true;

		final Iterator<PathElement> thisIt = this.iterator();
		final Iterator<PathElement> pathIt = path.iterator();

		while (thisIt.hasNext()) {
			assert pathIt.hasNext();
			final PathElement thisNext = thisIt.next();
			final PathElement pathNext = pathIt.next();
			assert thisNext != null;
			if (!thisNext.equals(pathNext)) return false;
		}

		return !pathIt.hasNext();
	}

	/**
	 * @return context getter
	 */
	public SimulationContext getContext() {
		return context;
	}

	public Path reversePath() {
		final ArrayPath arrayPath = new ArrayPath(getContext());
		for (Iterator<PathElement> iter = descendingIterator(); iter.hasNext();) {
			arrayPath.addLast(iter.next());
		}
		return arrayPath;
	}
}
