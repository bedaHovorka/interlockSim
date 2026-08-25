/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: recursive Swing component lookups
 */
package cz.vutbr.fit.interlockSim.testutil

import java.awt.Container

/**
 * The first component of type [T] anywhere under [container], searching depth-first, or `null`.
 *
 * Swing panel tests reach their widgets by walking the container tree, and five GUI test classes
 * each carried their own recursive `findComponent` / `findAllComponents` pair — around 30 lines
 * apiece (Issue #955, cluster U2).
 */
fun <T> findComponent(
	container: Container,
	type: Class<T>
): T? {
	for (child in container.components) {
		if (type.isInstance(child)) {
			@Suppress("UNCHECKED_CAST")
			return child as T
		}
		if (child is Container) {
			val found = findComponent(child, type)
			if (found != null) {
				return found
			}
		}
	}
	return null
}

/** Every component of type [T] anywhere under [container], in depth-first order. */
fun <T> findAllComponents(
	container: Container,
	type: Class<T>
): List<T> {
	val found = mutableListOf<T>()
	for (child in container.components) {
		if (type.isInstance(child)) {
			@Suppress("UNCHECKED_CAST")
			found.add(child as T)
		}
		if (child is Container) {
			found.addAll(findAllComponents(child, type))
		}
	}
	return found
}
