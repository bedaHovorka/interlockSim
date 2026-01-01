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

import jDisco.Continuous;

/**
 * Base of simple validation feature for continuous variables and processes
 *
 */
public abstract class AssertionContinuous extends Continuous {
	private static boolean assertionEnabled = false;
	private static boolean assertionForced = true;

	static {
		assert assertionEnabled = true;
		if (assertionForced && !assertionEnabled)
			throw new RuntimeException("Please enable assertion facility.");
	}

	@Override
	protected final void derivatives() {
		assert check() : report(new StringBuilder(Double.toString(time())).append(" : "));
	}

	/**
	 * @param reportObj
	 * @return Must return same reportObj back
	 */
	public abstract StringBuilder report(final StringBuilder reportObj);

	/**
	 * This metod describe conditions, which mean valid state
	 * @return condition result
	 */
	public abstract boolean check();

	@Override
	public AssertionContinuous start() {
		return (assertionEnabled) ? (AssertionContinuous) super.start() : this;
	}
}
