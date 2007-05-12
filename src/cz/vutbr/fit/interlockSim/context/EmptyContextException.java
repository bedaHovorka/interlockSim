/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

/**
 * In context be missing something
 */
public class EmptyContextException extends Exception {
	/**
	 * @see Exception#Exception()
	 */
	public EmptyContextException() {
		//EMPTY
	}

	/**
	 * @see Exception#Exception(String)
	 */
	public EmptyContextException(String message) {
		super(message);
	}

	/**
	 * @see Exception#Exception(Throwable)
	 */
	public EmptyContextException(Throwable cause) {
		super(cause);
	}

	/**
	 * @see Exception#Exception(String, Throwable)
	 */
	public EmptyContextException(String message, Throwable cause) {
		super(message, cause);
	}
}
