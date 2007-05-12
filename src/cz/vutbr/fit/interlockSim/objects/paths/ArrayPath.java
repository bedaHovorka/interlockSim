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

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;

import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.util.Util;

/**
 * ArrayDeque delegated implemetation of {@link Path}
 * There is nothing special to see, please try {@link AbstractPath}
 */
public final class ArrayPath extends AbstractPath {
	private final Deque<PathElement> deque = new ArrayDeque<PathElement>();

	/**
	 * @param context
	 */
	public ArrayPath(SimulationContext context) {
		super(context);
	}

	/**
	 * @param elements
	 * @return true if the collection changed as a result of the call
	 */
	public boolean addAll(PathElement...elements) {
		return Collections.addAll(deque, elements);
	}
	
	public boolean add(PathElement e) {
		return deque.add(e);
	}

	public boolean addAll(Collection<? extends PathElement> c) {
		return deque.addAll(c);
	}

	public void addFirst(PathElement e) {
		deque.addFirst(e);
	}

	public void addLast(PathElement e) {
		deque.addLast(e);
	}

	public void clear() {
		deque.clear();
	}

	public boolean contains(Object o) {
		return deque.contains(o);
	}

	public boolean containsAll(Collection<?> c) {
		return deque.containsAll(c);
	}

	public Iterator<PathElement> descendingIterator() {
		return deque.descendingIterator();
	}

	public PathElement element() {
		return deque.element();
	}

	public PathSeparator getFirst() {
		return Util.assertInstanceOf(PathSeparator.class, deque.getFirst());
	}

	public OrientedPathSeparator getLast() {
		return Util.assertInstanceOf(OrientedPathSeparator.class, deque.getLast());
	}

	public boolean isEmpty() {
		return deque.isEmpty();
	}

	public Iterator<PathElement> iterator() {
		return deque.iterator();
	}

	public boolean offer(PathElement e) {
		return deque.offer(e);
	}

	public boolean offerFirst(PathElement e) {
		return deque.offerFirst(e);
	}

	public boolean offerLast(PathElement e) {
		return deque.offerLast(e);
	}

	public PathElement peek() {
		return deque.peek();
	}

	public PathElement peekFirst() {
		return deque.peekFirst();
	}

	public PathElement peekLast() {
		return deque.peekLast();
	}

	public PathElement poll() {
		return deque.poll();
	}

	public PathElement pollFirst() {
		return deque.pollFirst();
	}

	public PathElement pollLast() {
		return deque.pollLast();
	}

	public PathElement pop() {
		return deque.pop();
	}

	public void push(PathElement e) {
		deque.push(e);
	}

	public PathElement remove() {
		return deque.remove();
	}

	public boolean remove(Object o) {
		return deque.remove(o);
	}

	public boolean removeAll(Collection<?> c) {
		return deque.removeAll(c);
	}

	public PathElement removeFirst() {
		return deque.removeFirst();
	}

	public boolean removeFirstOccurrence(Object o) {
		return deque.removeFirstOccurrence(o);
	}

	public PathElement removeLast() {
		return deque.removeLast();
	}

	public boolean removeLastOccurrence(Object o) {
		return deque.removeLastOccurrence(o);
	}

	public boolean retainAll(Collection<?> c) {
		return deque.retainAll(c);
	}

	public int size() {
		return deque.size();
	}

	public Object[] toArray() {
		return deque.toArray();
	}

	public <T> T[] toArray(T[] a) {
		return deque.toArray(a);
	}
	
	@Override
	public String toString() {
		return deque.toString();
	}
}
