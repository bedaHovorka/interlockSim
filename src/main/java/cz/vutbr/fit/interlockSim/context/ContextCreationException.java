/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

/**
 * Is throwing by {@link ContextFactory}, when source for context is wrong
 *
 */
public class ContextCreationException extends Exception {
	/**
	 * @see Exception#Exception()
	 *
	 */
	public ContextCreationException() {
		super();
	}

	/**
	 * @see Exception#Exception(Throwable)
	 * @param cause
	 */
	public ContextCreationException(Throwable cause) {
		super(cause);
	}

	/**
	 * @see Exception#Exception(String)
	 * @param string
	 */
	public ContextCreationException(String string) {
		super(string);
	}

}
