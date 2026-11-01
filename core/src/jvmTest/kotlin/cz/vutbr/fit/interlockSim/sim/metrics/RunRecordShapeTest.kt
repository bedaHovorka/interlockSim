/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * [RunRecord] is the finished projection of [RunEndSummary] (Issue #676, Goal 6 SP5): the two carry
 * the same figures under the same names, so a figure added to one fails here until it is added to
 * the other (and to `RunEndSummary.toRunRecord`). The one intended difference is the type, not the
 * name: `classification` and `endTime` are nullable in the summary and set in the record.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
class RunRecordShapeTest {
	private fun propertyNames(type: Class<*>): Set<String> =
		type.declaredFields
			.filterNot { Modifier.isStatic(it.modifiers) }
			.map { it.name }
			.toSet()

	@Test
	fun `the record and the summary have the same field names`() {
		assertThat(propertyNames(RunRecord::class.java)).isEqualTo(propertyNames(RunEndSummary::class.java))
	}

	/**
	 * Nullability of an object type is invisible to reflection on the JVM, so of the two intended
	 * differences only `endTime` shows: `Double?` boxes in the summary, `Double` is primitive in the
	 * record. Any other type difference is a field changed in one class only.
	 */
	@Test
	fun `the end time is the one field whose JVM type differs`() {
		val differingTypes =
			RunEndSummary::class.java.declaredFields
				.filterNot { Modifier.isStatic(it.modifiers) }
				.filter { field -> RunRecord::class.java.getDeclaredField(field.name).type != field.type }
				.map { it.name }
				.toSet()

		assertThat(differingTypes).isEqualTo(setOf("endTime"))
	}
}
