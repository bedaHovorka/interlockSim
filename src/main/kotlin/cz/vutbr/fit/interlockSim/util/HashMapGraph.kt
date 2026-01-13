/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import java.util.AbstractCollection
import java.util.ArrayList
import java.util.Collection
import java.util.HashMap
import java.util.HashSet
import java.util.Iterator
import java.util.Map
import java.util.Map.Entry
import java.util.Set

/**
 * The ADT ExtendedUnorientedGraph prototype
 *
 * @param <N> nodes
 * @param <E> edges
 * @param <X> {@link ExtendedUnorientedGraph}
 */
class HashMapGraph<N, E, X> :
	AbstractUnorientedGraph<N, E>(),
	ExtendedUnorientedGraph<N, E, X> {
	inner class NodeCollection : AbstractCollection<N>() {
		private inner class NodeCollectionIterator : MutableIterator<N> {
			private val keySetIterator: java.util.Iterator<Doubleton<N, X>> =
				keySet.iterator() as java.util.Iterator<Doubleton<N, X>>
			private var currentPair: Iterator<N>? = null
			private var current: N? = null

			/**
			 * Construct iterator for iterating over nodes
			 */
			init {
				if (keySetIterator.hasNext()) {
					currentPair = keySetIterator.next().iterator() as Iterator<N>
				}
			}

			override fun remove() {
				if (current == null) throw IllegalStateException()
				this@HashMapGraph.removeAll(current!!)
				current = null
			}

			override fun next(): N {
				if (currentPair?.hasNext() != true) {
					currentPair = keySetIterator.next().iterator() as Iterator<N>
				}
				current = currentPair!!.next()
				return current!!
			}

			override fun hasNext(): Boolean =
				(currentPair != null && currentPair!!.hasNext()) ||
					keySetIterator.hasNext()
		}

		private val keySet: java.util.Set<Doubleton<N, X>> = map.keys as java.util.Set<Doubleton<N, X>>

		override fun iterator(): MutableIterator<N> = NodeCollectionIterator()

		override val size: Int
			get() = keySet.size shl 1
	}

	private var nodeCollection: NodeCollection? = null

	private val map = HashMap<Doubleton<N, X>, E>()

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#put(N, N, I)
	 */
	override fun put(
		first: N,
		second: N,
		value: E
	) {
		map[Doubleton<N, X>(first, second)] = value
	}

	override fun put(
		first: N,
		firstAddInf: X,
		second: N,
		secondAddInf: X,
		value: E
	) {
		map[Doubleton(first, second, firstAddInf, secondAddInf)] = value
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#get(N, N)
	 */
	override fun get(
		first: N,
		second: N
	): E? = map[getReferencer(first, second)]

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#remove(N, N)
	 */
	override fun remove(
		first: N,
		second: N
	): E? = map.remove(getReferencer(first, second))

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#removeAll(N)
	 */
	override fun removeAll(node: N): Collection<E> = allEdgesJoinsWith(node, true)

	private fun allEdgesJoinsWith(
		node: N,
		remove: Boolean
	): Collection<E> {
		requireValidState(node != null) { "Node cannot be null" }
		val collection = ArrayList<E>()

		val iterator = map.entries.iterator()
		while (iterator.hasNext()) {
			val next = iterator.next()
			val key = next.key
			if (key.contains(node)) {
				collection.add(next.value)
				if (remove) iterator.remove()
			}
		}
		return collection as java.util.Collection<E>
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#remove(I)
	 */
	override fun remove(h: E): Collection<N> {
		requireValidState(h != null) { "Edge cannot be null" }
		val collection = HashSet<N>()
		val iterator = map.entries.iterator()
		while (iterator.hasNext()) {
			val next = iterator.next()
			if (h == next.value) {
				collection.addAll(next.key)
				iterator.remove()
			}
		}
		return collection as java.util.Collection<N>
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#nodeSet()
	 */
	override fun nodeSet(): Set<N> {
		if (nodeCollection == null) nodeCollection = NodeCollection()
		// TODO: Return unmodifiable view instead of copy - see issue #59
		val set = HashSet<N>(nodeCollection)
		// for (Doubleton<N> c: map.keySet()) {
		// 	set.addAll(c);
		// }
		return set as java.util.Set<N>
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#entrySet()
	 */
	override fun entrySet(): Set<Map.Entry<Doubleton<N, X>, E>> {
		@Suppress("UNCHECKED_CAST")
		return map.entries as java.util.Set<Map.Entry<Doubleton<N, X>, E>>
	}

	override fun putIfNotExists(
		first: N,
		addInfFirst: X,
		second: N,
		addInfSecond: X,
		edge: E
	) {
		val pair = Doubleton<N, X>(first, second, addInfFirst, addInfSecond)
		if (!map.containsKey(pair)) {
			map[pair] = edge
		}
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#values()
	 */
	override fun values(): Collection<E> {
		@Suppress("UNCHECKED_CAST")
		return map.values as Collection<E>
	}

	override fun get(node: N): Collection<E> = allEdgesJoinsWith(node, false)

	private fun getReferencer(
		first: N,
		second: N
	): Doubleton<N, X> = Doubleton(first, second)

	override fun assignedEdges(node: N): Map<X, E> {
		val lmap = HashMap<X, E>()
		(
			object : DoubletonEntrySetProcessor<X>(map as java.util.Map<Doubleton<N, X>, E>) {
				override fun processEntryNode(
					key: Doubleton<N, X>,
					edge: E,
					node2: N
				) {
					if (node == node2) {
						val aInf = key.getValue(node)
						requireValidState(!lmap.containsKey(aInf)) { "Duplicate additional info key $aInf for node $node" }
						lmap[aInf!!] = edge
					}
				}
			}
		).process()
		return lmap as java.util.Map<X, E>
	}

	override fun extensionalObject(
		node: N,
		edge: E
	): X {
		val p =
			object : DoubletonEntrySetProcessor<X>(map as java.util.Map<Doubleton<N, X>, E>) {
				override fun processEntryNode(
					key: Doubleton<N, X>,
					edge2: E,
					node2: N
				) {
					if (node == node2 && edge == edge2) {
						requireValidState(getResult() == null) { "Multiple extensional objects found for node $node and edge $edge" }
						setResult(key.getValue(node)!!)
					}
				}
			}
		p.process()
		return p.getResult()!!
	}

	abstract inner class DoubletonEntrySetProcessor<T>(
		private val map2: java.util.Map<Doubleton<N, X>, E>
	) {
		private var result: T? = null

		fun process() {
			@Suppress("UNCHECKED_CAST")
			val entries = (map2 as java.util.HashMap<Doubleton<N, X>, E>).entries
			for (e in entries) {
				val key = e.key
				val iterator = key.iterator()
				while (iterator.hasNext()) {
					val next = iterator.next()
					processEntryNode(key, e.value, next)
				}
			}
		}

		abstract fun processEntryNode(
			key: Doubleton<N, X>,
			edge: E,
			node: N
		)

		fun getResult(): T? = result

		fun setResult(result: T) {
			this.result = result
		}
	}

	override fun contains(
		node1: N,
		node2: N
	): Boolean = map.containsKey(getReferencer(node1, node2))

	override fun implementationContainer(): HashMap<Doubleton<N, X>, E> = map

	override fun size(): Int = map.size

	override fun clear() {
		map.clear()
	}
}
