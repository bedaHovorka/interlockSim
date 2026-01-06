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

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

/**
 * for enum pairs mapping to other
 * @param <N> node
 * @param <E> edge
 */
public class EnumUnorientedGraph<N extends Enum<N>, E> extends AbstractUnorientedGraph<N,E> {
	private final Map<N, Map<N, E>> map;
	private final Class<N> clazz;

	/**
	 * @param clazz
	 */
	public EnumUnorientedGraph(Class<N> clazz) {
		this.clazz = clazz;
		this.map = new EnumMap<N, Map<N, E>>(clazz);
	}

	@Override
	protected Object implementationContainer() {
		return map;
	}

	public boolean contains(N node1, N node2) {
		final N[] ns = nodesInOrder(node1, node2);
		final Map<N, E> submap = getSubmap(ns);
		if (submap == null) return false;
		return submap.containsKey(ns[1]);
	}

	public E get(N first, N second) {
		final N[] ns = nodesInOrder(first, second);
		final Map<N, E> submap = getSubmap(ns);
		if (submap == null) return null;
		return submap.get(ns[1]);
	}

	public Collection<E> get(N node) {
		throw new NotImplementedException();
	}

	/**
	 * @param node
	 * @return node around in graph
	 */
	public Map<N, E> getJoinedNodesAndEdges(N node) {
		final EnumMap<N, E> enumMap = new EnumMap<N, E>(clazz);
		for (Entry<N, Map<N,E>> e : map.entrySet()) {
			final N key = e.getKey();
			final Map<N, E> value = e.getValue();
			assert !value.containsKey(key) : value;

			if (key == node) {
				enumMap.putAll(value);
			} else {
				for (Entry<N, E> e2: value.entrySet()) {
					final N key2 = e2.getKey();
					if (key2 == node) {
						assert !enumMap.containsKey(key) : enumMap;
						enumMap.put(key, e2.getValue());
					}
				}
			}
		}
		return enumMap;
	}

	public Set<N> nodeSet() {
		throw new NotImplementedException();
	}

	public void put(N first, N second, E value) {
		final N[] ns = nodesInOrder(first, second);
		final Map<N, E> submap = getSubmapWithCreation(ns);
		submap.put(ns[1], value);
	}

	public E remove(N first, N second) {
		throw new NotImplementedException();
	}

	public Collection<N> remove(E edge) {
		throw new NotImplementedException();
	}

	public Collection<E> removeAll(N node) {
		throw new NotImplementedException();
	}

	public Collection<E> values() {
		throw new NotImplementedException();
	}

	private N[] nodesInOrder(N n1, N n2) {
		assert n1 != n2;
		final N[] nodes = (N[]) new Enum[]{n1,n2};
		Arrays.sort(nodes);
		return nodes;
	}

	private Map<N, E> getSubmap(N[] sortedNodes) {
		return map.get(sortedNodes[0]);
	}

	private Map<N, E> getSubmapWithCreation(N[] sortedNodes) {
		Map<N, E> subMap = getSubmap(sortedNodes);
		if (subMap == null) {
			subMap = new EnumMap<N, E>(clazz);
			map.put(sortedNodes[0], subMap);
		}
		return subMap;
	}
}
