/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util;

/**
 * Debug utility
 */
public class NotImplementedException extends RuntimeException {
	private static final long serialVersionUID = 1;
	/**
	 * @see Exception#Exception()
	 */
	public NotImplementedException() {
		super();
	}
	/**
	 * @see Exception#Exception(String)
	 */
	public NotImplementedException(String message) {
		super(message);
	}
	/**
	 * @see Exception#Exception(String, Throwable)
	 * @param message
	 * @param cause
	 */
	public NotImplementedException(String message, Throwable cause) {
		super(message, cause);
	}
}
