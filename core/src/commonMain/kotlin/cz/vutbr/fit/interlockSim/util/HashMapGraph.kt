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

/**
 * Hash map based implementation of an unoriented graph.
 *
 * @param <N> nodes
 * @param <E> edges
 * @param <X> {@link ExtendedUnorientedGraph}
 *
 * ## Thread Safety
 *
 * **This class is NOT thread-safe.**
 *
 * The nodeSet() cache (lines ~191-203) uses lazy initialization without synchronization:
 * - Non-volatile fields: `nodeCollection`, `cachedNodeSet`
 * - Check-then-act race condition in nodeSet()
 * - No synchronization on cache invalidation
 *
 * ### Design Decision
 *
 * Thread safety is intentionally NOT implemented because:
 * 1. BaseContext (which uses HashMapGraph) operates in a single thread
 * 2. Swing GUI editor runs on Event Dispatch Thread (EDT)
 * 3. kDisco discrete event simulation framework is single-threaded
 * 4. No current use cases require concurrent access to the graph
 * 5. Thread-safety would add complexity and performance overhead
 *
 * ### Usage Guidelines
 *
 * - ✅ Safe: Single-threaded access (GUI, simulation)
 * - ✅ Safe: Frozen contexts with concurrent reads (70%+ success, see ContextConcurrencyTest)
 * - ❌ Unsafe: Concurrent modifications from multiple threads
 * - ❌ Unsafe: Mixed read/write access without synchronization
 *
 * @see cz.vutbr.fit.interlockSim.context.BaseContext for context-level thread-safety documentation
 * @see cz.vutbr.fit.interlockSim.context.ContextConcurrencyTest for race condition behavior tests
 */
class HashMapGraph<N, E, X> :
	AbstractUnorientedGraph<N, E>(),
	ExtendedUnorientedGraph<N, E, X> {
	inner class NodeCollection : AbstractMutableCollection<N>() {
		private inner class NodeCollectionIterator : MutableIterator<N> {
			private val keySetIterator: MutableIterator<Doubleton<N, X>> = keySet.iterator()
			private var currentPair: MutableIterator<N>? = null
			private var current: N? = null

			/**
			 * Construct iterator for iterating over nodes
			 */
			init {
				if (keySetIterator.hasNext()) {
					currentPair = keySetIterator.next().iterator()
				}
			}

			override fun remove() {
				val node = checkNotNull(current)
				this@HashMapGraph.removeAll(node)
				current = null
			}

			override fun next(): N {
				if (currentPair?.hasNext() != true) {
					currentPair = keySetIterator.next().iterator()
				}
				current = currentPair!!.next()
				return current!!
			}

			override fun hasNext(): Boolean =
				(currentPair != null && currentPair!!.hasNext()) ||
					keySetIterator.hasNext()
		}

		private val keySet: MutableSet<Doubleton<N, X>> = map.keys

		override fun add(element: N): Boolean = throw UnsupportedOperationException()

		override fun iterator(): MutableIterator<N> = NodeCollectionIterator()

		override val size: Int
			get() = keySet.size shl 1
	}

	private var nodeCollection: NodeCollection? = null
	private var cachedNodeSet: MutableSet<N>? = null

	private val map: MutableMap<Doubleton<N, X>, E> = mutableMapOf()

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#put(N, N, I)
	 */
	override fun put(
		first: N,
		second: N,
		value: E
	) {
		map[Doubleton<N, X>(first, second)] = value
		// Invalidate cached node set when graph structure changes
		cachedNodeSet = null
	}

	override fun put(
		first: N,
		firstExt: X,
		second: N,
		secondExt: X,
		value: E
	) {
		map[Doubleton(first, second, firstExt, secondExt)] = value
		// Invalidate cached node set when graph structure changes
		cachedNodeSet = null
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
	): E? {
		val result = map.remove(getReferencer(first, second))
		// Invalidate cached node set when graph structure changes
		if (result != null) cachedNodeSet = null
		return result
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#removeAll(N)
	 */
	override fun removeAll(node: N): Collection<E> = allEdgesJoinsWith(node, true)

	private fun allEdgesJoinsWith(
		node: N,
		remove: Boolean
	): Collection<E> {
		requireValidState(node != null) { "Node cannot be null" }
		val collection = mutableListOf<E>()

		val iterator = map.entries.iterator()
		var hasRemoved = false
		while (iterator.hasNext()) {
			val next = iterator.next()
			val key = next.key
			if (key.contains(node)) {
				collection.add(next.value)
				if (remove) {
					iterator.remove()
					hasRemoved = true
				}
			}
		}
		// Invalidate cached node set when graph structure changes
		if (hasRemoved) cachedNodeSet = null
		return collection
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#remove(I)
	 */
	override fun remove(edge: E): Collection<N> {
		requireValidState(edge != null) { "Edge cannot be null" }
		val collection = mutableSetOf<N>()
		val iterator = map.entries.iterator()
		var hasRemoved = false
		while (iterator.hasNext()) {
			val next = iterator.next()
			if (edge == next.value) {
				collection.addAll(next.key)
				iterator.remove()
				hasRemoved = true
			}
		}
		// Invalidate cached node set when graph structure changes
		if (hasRemoved) cachedNodeSet = null
		return collection
	}

	/**
	 * Returns an unmodifiable view of all nodes in the graph.
	 *
	 * **Caching Strategy:**
	 * - Lazy initialization: nodeCollection and cachedNodeSet created on first access
	 * - Cache invalidation: Set to null on any graph modification (put, remove, clear)
	 * - Not thread-safe: No synchronization (see class-level documentation)
	 *
	 * **Thread-Safety Note:**
	 * This method has a check-then-act race condition. Multiple threads calling this
	 * method concurrently may create duplicate cached sets. This is acceptable because:
	 * - HashMapGraph is documented as not thread-safe
	 * - No current use cases involve concurrent access
	 * - Frozen contexts (via BaseContext.freeze()) provide safer concurrent reads
	 *
	 * @return Unmodifiable set of all nodes
	 */
	override fun nodeSet(): Set<N> {
		if (nodeCollection == null) nodeCollection = NodeCollection()

		// Lazily build and cache the node set (materializes once, then reuses)
		if (cachedNodeSet == null) {
			cachedNodeSet = nodeCollection!!.toMutableSet()
		}

		// Return unmodifiable view of cached set (O(1) performance)
		return cachedNodeSet!!.toSet()
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#entrySet()
	 */
	override fun entrySet(): Set<Map.Entry<Doubleton<N, X>, E>> {
		// Return unmodifiable view of map entries (O(1) performance)
		@Suppress("UNCHECKED_CAST")
		return map.entries.toSet() as Set<Map.Entry<Doubleton<N, X>, E>>
	}

	override fun putIfNotExists(
		first: N,
		firstExt: X,
		second: N,
		secondExt: X,
		edge: E
	) {
		val pair = Doubleton<N, X>(first, second, firstExt, secondExt)
		if (!map.containsKey(pair)) {
			map[pair] = edge
			// Invalidate cached node set when graph structure changes
			cachedNodeSet = null
		}
	}

	/* (non-Javadoc)
	 * @see cz.vutbr.fit.interlockSim.context.Graph#values()
	 */
	override fun values(): Collection<E> = map.values.toList()

	override fun get(node: N): Collection<E> = allEdgesJoinsWith(node, false)

	private fun getReferencer(
		first: N,
		second: N
	): Doubleton<N, X> = Doubleton(first, second)

	override fun assignedEdges(node: N): Map<X, E> {
		val lmap = mutableMapOf<X, E>()
		(
			object : DoubletonEntrySetProcessor<X>(map) {
				override fun processEntryNode(
					key: Doubleton<N, X>,
					entryEdge: E,
					entryNode: N
				) {
					if (node == entryNode) {
						val aInf = key.getValue(node)
						requireValidState(!lmap.containsKey(aInf)) { "Duplicate additional info key $aInf for node $node" }
						lmap[aInf!!] = entryEdge
					}
				}
			}
		).process()
		return lmap
	}

	override fun extensionalObject(
		node: N,
		edge: E
	): X {
		val p =
			object : DoubletonEntrySetProcessor<X>(map) {
				override fun processEntryNode(
					key: Doubleton<N, X>,
					entryEdge: E,
					entryNode: N
				) {
					if (node == entryNode && edge == entryEdge) {
						requireValidState(result == null) {
							"Multiple extensional objects found for node $node and edge $edge"
						}
						result = key.getValue(node)!!
					}
				}
			}
		p.process()
		return p.result!!
	}

	abstract inner class DoubletonEntrySetProcessor<T>(
		private val map2: MutableMap<Doubleton<N, X>, E>
	) {
		/**
		 * Result collected while processing the entry set, or null if nothing matched yet.
		 */
		var result: T? = null

		fun process() {
			val entries = map2.entries
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
			entryEdge: E,
			entryNode: N
		)
	}

	override fun contains(
		node1: N,
		node2: N
	): Boolean = map.containsKey(getReferencer(node1, node2))

	override fun implementationContainer(): HashMap<Doubleton<N, X>, E> = map as HashMap<Doubleton<N, X>, E>

	override fun size(): Int = map.size

	override fun clear() {
		map.clear()
		// Invalidate cached node set when graph structure changes
		cachedNodeSet = null
	}
}
