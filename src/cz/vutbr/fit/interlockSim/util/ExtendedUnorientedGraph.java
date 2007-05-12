/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util;

import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

/**
 * for this program extended unoriented graph interface
 *
 * @param <N> nodes
 * @param <E> edges
 * @param <X> node to edge relation extensional object
 */
public interface ExtendedUnorientedGraph<N,E,X> extends UnorientedGraph<N, E> {
	
	/**
	 * Put element to ExtendedUnorientedGraph with additional info
	 * @param first
	 * @param firstExt
	 * @param second
	 * @param secondExt
	 * @param value
	 */
	public void put(N first, X firstExt, N second, X secondExt, E value);
	
	/**
	 * 
	 * @param node
	 * @param edge
	 * @return object mapped to relation between node and edge
	 */
	public X extensionalObject(N node, E edge);
	
	/**
	 * @param node
	 * @return extensioanal objects mapped to edge, which joins with mode
	 */
	public Map<X, E> assignedEdges(N node);
	
	/**
	 * @see Map#entrySet()
	 * @return entry set
	 */
	public Set<Entry<Doubleton<N,X>, E>> entrySet();

	/**
	 * Put the key if not present in graph
	 * @param first 
	 * @param firstExt 
	 * @param second 
	 * @param secondExt 
	 * @param edge 
	 */
	public void putIfNotExists(N first, X firstExt, N second, X secondExt, E edge);
}