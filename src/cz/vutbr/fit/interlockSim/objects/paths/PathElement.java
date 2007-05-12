/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths;

/**
 * Element of {@link Path}
 *
 */
public interface PathElement {
	/**
	 * model constant
	 */
	public static final double COMMON_MAX_SPEED = 24; // in m/s
	/**
	 * model constant
	 */
	public static final double ABSOLUTE_MAX_SPEED = 90;//in m/s
	/**
	 * model constant
	 */
	public static final double MINIMAL_MAX_SPEED = 2;//in m/s
}
