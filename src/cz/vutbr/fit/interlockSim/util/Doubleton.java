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

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Pair of nodes for implementation ADT ExtendedUnorientedGraph
 * Represents combination of two elements
 * @param <T> type of elements
 * @param <V> type of additional information
 */
public class Doubleton<T,V> extends AbstractSet<T> {
	enum IteratorState {
		INIT, 
		FIRST,
		SECOND;
	}
	
	final class DoubletonIterator implements Iterator<T> {
		IteratorState state = IteratorState.INIT;
		
		public boolean hasNext() {
			assert state != null;
			return state != IteratorState.SECOND;
		}

		public T next() {
			assert state != null;
			if (state == IteratorState.INIT) {
				state = IteratorState.FIRST;
				return first;
			} else if (state == IteratorState.FIRST) {
				state = IteratorState.SECOND;
				return second;
			} else throw new NoSuchElementException();
		}

		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	private final T first;
	private final T second;
	private V firstValue;
	private V secondValue;
	
	/**
	 * Create with additional information
	 * @param first
	 * @param second
	 * @param firstValue
	 * @param secondValue
	 */
	public Doubleton(final T first, final T second, V firstValue, V secondValue) {
		this(first, second);
		this.firstValue = firstValue;
		this.secondValue = secondValue;
	}

	/**
	 * Create without additional information
	 * @param first
	 * @param second
	 */
	public Doubleton(final T first, final T second) {
		if (first == second || (first != null && first.equals(second))) throw new IllegalArgumentException("arguments is equal");
		this.first = first;
		this.second = second;
	}
	
	@Override
	public int hashCode() {
		int result = (first == null) ? 0 : first.hashCode();
		result += (second == null) ? 0 : second.hashCode();//kongruentni komutativita?
		return result;
	}
	
	@Override
	public Iterator<T> iterator() {
		return new DoubletonIterator();
	}

	@Override
	public int size() {
		return 2;
	}
	
	@Override
	public boolean isEmpty() {
		return false;
	}
	
	/**
	 * @param key
	 * @return value assigned to key
	 */
	public V getValue(T key) {
		final boolean secondEq = nullEq(key, second);
		if (nullEq(key, first)) {
			assert !secondEq;
			return firstValue;
		}	
		if (secondEq) return secondValue;
		throw new IllegalArgumentException("Key " + key + " is not contain in set");
	}
	
	private boolean nullEq(final Object a, final Object b) {
		if (a == null) return b == null;
		return b != null && a.equals(b);
	}
	
	/**
	 * @param first
	 * @param second
	 */
	public void setValues(V first, V second) {
		this.firstValue = first;
		this.secondValue = second;
	}
}
