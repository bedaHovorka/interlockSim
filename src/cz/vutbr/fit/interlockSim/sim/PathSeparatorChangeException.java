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

import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;

/**
 * @author beda
 *
 */
public class PathSeparatorChangeException extends SimulationException {
	/**
	 * @param object
	 */
	public PathSeparatorChangeException(PathSeparator object) {
		super(object);
	}

	/**
	 * @param message
	 * @param object
	 */
	public PathSeparatorChangeException(String message, PathSeparator object) {
		super(message, object);
	}

	/**
	 * @param cause
	 * @param object
	 */
	public PathSeparatorChangeException(Throwable cause, PathSeparator object) {
		super(cause, object);
	}

	/**
	 * @param message
	 * @param cause
	 * @param object
	 */
	public PathSeparatorChangeException(String message, Throwable cause, PathSeparator object) {
		super(message, cause, object);
	}
	
	@Override
	public PathSeparator getObject() {
		return (PathSeparator) super.getObject();
	}
}
