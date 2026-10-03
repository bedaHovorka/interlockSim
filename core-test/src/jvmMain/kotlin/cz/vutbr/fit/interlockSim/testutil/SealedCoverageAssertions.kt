/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * AssertK extension for sample tables that must cover a sealed hierarchy (JVM only: needs
 * `sealedSubclasses`, which needs kotlin-reflect at runtime).
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.Assert
import assertk.assertions.support.expected
import kotlin.reflect.KClass

/**
 * Asserts that the classes of the samples are exactly the leaf subclasses of [sealed]: one sample
 * class per leaf, none missing, none extra. A sealed subclass of a sealed class is replaced by its
 * own subclasses, so a deeper hierarchy is covered too.
 *
 * Use it on a parameterized test's sample table so that a new subtype fails the build until the
 * table names it.
 */
fun Assert<Iterable<Any>>.coversEverySealedSubclassOf(sealed: KClass<*>) {
	given { samples ->
		val leaves = sealedLeaves(sealed)
		val covered = samples.map { it::class }.toSet()
		if (covered != leaves) {
			expected(
				"the samples to cover exactly the subclasses of ${sealed.simpleName}: " +
					"missing ${leaves - covered}, unexpected ${covered - leaves}"
			)
		}
	}
}

private fun sealedLeaves(sealed: KClass<*>): Set<KClass<*>> =
	sealed.sealedSubclasses
		.flatMap { sub -> if (sub.isSealed) sealedLeaves(sub) else setOf(sub) }
		.toSet()
