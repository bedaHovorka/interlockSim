/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells;

import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;

/**
 * Node managed by dispatcher (if is in main graph)
 *
 */
public abstract class NodeCell extends AbstractCell implements PathSeparator { 
	private final SpatialType spatialType;
	private String toString = "";
	private String name = "";
	
	/**
      * @param spatialType
      */
	public NodeCell(SpatialType spatialType) {
		this.spatialType = spatialType;
	}
	
	public SpatialType getSpatialType() {
		return spatialType;
	}
	
	/**
	 * setter
	 * @param name
	 */
	public void setName(String name) {
		this.name = name;
		toString = getClass().getSimpleName() + ':' + String.valueOf(name);
	}

	/**
	 * 
	 * @return
	 */
	public String getName() {
		return name;
	}
	
	@Override
	public String toString() {
		return toString;
	}
}