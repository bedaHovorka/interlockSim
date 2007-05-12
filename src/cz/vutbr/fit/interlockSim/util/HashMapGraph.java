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

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

/**
 * The ADT ExtendedUnorientedGraph prototype
 *
 * @param <N> nodes
 * @param <E> edges
 * @param <X> {@link ExtendedUnorientedGraph}
 */
public class HashMapGraph<N,E,X> extends AbstractUnorientedGraph<N, E> implements ExtendedUnorientedGraph<N,E,X> {
	final class NodeCollection extends AbstractCollection<N> {
		private final class NodeCollectionIterator implements Iterator<N> {
			private final Iterator<Doubleton<N,X>> keySetIterator = keySet.iterator();
			private Iterator<N> currentPair;
			private N current;

			/**
			 * Construct iterator for iterating over nodes
			 */
			public NodeCollectionIterator() {
				super();
				if (keySetIterator.hasNext()) {
					currentPair = keySetIterator.next().iterator();
				}
			}

			public void remove() {
				if (current == null) throw new IllegalStateException();
				HashMapGraph.this.removeAll(current);
				current = null;
			}

			public N next() {
				if (!currentPair.hasNext()) {
					currentPair = keySetIterator.next().iterator();
				}
				current = currentPair.next();
				return current;
			}

			public boolean hasNext() {
				return (currentPair != null && currentPair.hasNext()) || 
						(keySetIterator != null && keySetIterator.hasNext());
			}
		}

		private final Set<Doubleton<N,X>> keySet = map.keySet();

		@Override
		public Iterator<N> iterator() {
			return new NodeCollectionIterator();
		}

		@Override
		public int size() {
			return keySet.size() << 1;
		}
	}
	
	private NodeCollection nodeCollection;
	
	private HashMap<Doubleton<N,X>, E> map = new HashMap<Doubleton<N,X>, E>();
	
	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#put(N, N, I)
	 */
	public void put(N first, N second, E value) {
	    map.put(new Doubleton<N,X>(first, second), value);
	}
	
	public void put(N first, X firstAddInf, N second, X secondAddInf, E value) {
		map.put(new Doubleton<N,X>(first, second, firstAddInf, secondAddInf), value);
	}
	
	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#get(N, N)
	 */
	public E get(N first, N second) {
	    return map.get(getReferencer(first, second));
	}
	
	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#remove(N, N)
	 */
	public E remove(N first, N second) {
	    return map.remove(getReferencer(first, second));
	}
	
	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#removeAll(N)
	 */
	public Collection<E> removeAll(N node) {
		return allEdgesJoinsWith(node, true);
	}
	
	private Collection<E> allEdgesJoinsWith(N node, boolean remove) {
		assert node != null;
		final Collection<E> collection = new ArrayList<E>();
		
		final Iterator<Entry<Doubleton<N,X>, E>> iterator = map.entrySet().iterator();
		while (iterator.hasNext()) {
			final Entry<Doubleton<N,X>, E> next = iterator.next();
			final Doubleton<N,X> key = next.getKey();
			if (key.contains(node)) {
				collection.add(next.getValue());
				if (remove) iterator.remove();
		    }
		}
		return collection;
	}
	
	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#remove(I)
	 */
	public Collection<N> remove(E h) {
	    assert h != null;
	    final Collection<N> collection = new HashSet<N>();
	    final Iterator<Entry<Doubleton<N,X>, E>> iterator = map.entrySet().iterator();
	    while (iterator.hasNext()) {
	    	final Entry<Doubleton<N,X>, E> next = iterator.next();
	    	if (h.equals(next.getValue())) {
	    		collection.addAll(next.getKey());
	    		iterator.remove();
	    	}
	    }
	    return collection;
	}
	
	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#nodeSet()
	 */
	public Set<N> nodeSet() {
	    if (nodeCollection == null) nodeCollection = new NodeCollection();
		//EXTENSION pohled ne copii
		Set<N> set = new HashSet<N>(nodeCollection);
		//for (Doubleton<N> c: map.keySet()) {
		//	set.addAll(c);
		//}
		return set;
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#entrySet()
	 */
	public Set<Entry<Doubleton<N,X>, E>> entrySet() {
		return map.entrySet();
	}

	public void putIfNotExists(N first, X addInfFirst, N second, X addInfSecond, E edge) {
		final Doubleton<N,X> pair = new Doubleton<N,X>(first, second, addInfFirst, addInfSecond);  
		if (!map.containsKey(pair)) {
			map.put(pair, edge);
		}
	}
	
	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#values()
	 */
	public Collection<E> values() {
		return map.values();
	}

	public Collection<E> get(N node) {
		return allEdgesJoinsWith(node, false);
	}

	private Doubleton<N, X> getReferencer(N first, N second) {
		return new Doubleton<N, X>(first, second);
	}
	
	public Map<X, E> assignedEdges(final N node) {
		final HashMap<X, E> lmap = new HashMap<X, E>();
		(new DoubletonEntrySetProcessor<X>(map) {
			@Override
			public void processEntryNode(Doubleton<N, X> key, E edge, N node2) {
				if (node.equals(node2)) {
					final X aInf = key.getValue(node);
					assert !lmap.containsKey(aInf);
					lmap.put(aInf, edge); 
				}
			}
		}).process();
		return lmap;
	}

	public X extensionalObject(final N node, final E edge) {
		final DoubletonEntrySetProcessor<X> p = (new DoubletonEntrySetProcessor<X>(map) {
					@Override
					public void processEntryNode(Doubleton<N, X> key, E edge2, N node2) {
						if (node.equals(node2) && edge == edge2) {
							assert getResult() == null;
							setResult(key.getValue(node)); 
						}
					}
				});
		p.process();
		return p.getResult();
	}
	
	abstract class DoubletonEntrySetProcessor <T> {
		private final Map<Doubleton<N, X>, E> map2;

		DoubletonEntrySetProcessor(Map<Doubleton<N, X>, E> map) {
			map2 = map;
		}
		
		private T result;
		
		void process() {
			for (Map.Entry<Doubleton<N, X>, E> e : map2.entrySet()) {
				final Doubleton<N, X> key = e.getKey();
				final Iterator<N> iterator = key.iterator();
				while (iterator.hasNext()) {
					final N next = iterator.next();
					processEntryNode(key, e.getValue(), next);
				}
			}
		}
		
		abstract void processEntryNode(Doubleton<N, X> key, E edge, N node);

		T getResult() {
			return result;
		}

		void setResult(T result) {
			this.result = result;
		}
	}

	public boolean contains(N node1, N node2) {
		return map.containsKey(getReferencer(node1, node2));
	}

	@Override
	protected HashMap<Doubleton<N, X>, E> implementationContainer() {
		return map;
	}
	
	@Override
	public int size() {
		return map.size();
	}
}
