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

import jDisco.Process;

/**
 *
 */
public class SimulationException extends Exception {
	private final Object object;
	private final double time;
	/**
	 *
	 */
	public SimulationException() {
		this((Object)null);
	}

	/**
	 * @param object
	 *
	 */
	public SimulationException(Object object) {
		this("", object);
	}

	/**
	 * @param message
	 */
	public SimulationException(String message) {
		this(message, null);
	}

	/**
	 * @param message
	 * @param object
	 */
	public SimulationException(String message, Object object) {
		this(message, null, object);
	}

	/**
	 * @param cause
	 */
	public SimulationException(Throwable cause) {
		this(cause, null);
	}

	/**
	 * @param cause
	 * @param object
	 */
	public SimulationException(Throwable cause, Object object) {
		this("", cause, object);
	}

	/**
	 * @param message
	 * @param cause
	 * @param object
	 */
	public SimulationException(String message, Throwable cause, Object object) {
		super(message, cause);
		this.object = object;
		this.time = Process.time();
	}

	/**
	 * @return object getter
	 */
	public Object getObject() {
		return object;
	}

	/**
	 * @return model time of exception
	 */
	public double getTime() {
		return time;
	}

	@Override
	public String getMessage() {
		return getTime() + ":" + String.valueOf(getObject()) + " " + super.getMessage();
	}
}
