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

/**
 * orientovany prvek
 * "ma nejaky vyznam a ten plati jen v jednom smeru jizdy"
 * muze byt zacatkem a koncem cesty
 */
public interface OrientedPathSeparator extends PathSeparator {
	/**
	 * @return orientation atribute
	 */
	public boolean getOrientation();

	/**
	 * @return direction segment
	 */
	public Segment direction();
}
