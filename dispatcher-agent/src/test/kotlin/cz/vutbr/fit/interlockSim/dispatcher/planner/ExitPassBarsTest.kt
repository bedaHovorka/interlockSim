/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.all
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.ExampleNames
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier

/**
 * The per-example exit bar table (Issue #1315): which example gets which bar, how an LLM twin
 * inherits its control arm's bar, and that an unknown name gets none.
 *
 * @since Issue #1315 (Goal 6)
 */
class ExitPassBarsTest {
	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(
		"${ExampleNames.SHUNTING_LOOP}, 6",
		"${ExampleNames.SHUNTING_LOOP_AI}, 6",
		"${ExampleNames.SHUNTING_LOOP_SYNC}, 7",
		"${ExampleNames.CERVENY_UJEZD}, 10",
		"${ExampleNames.CERVENY_UJEZD_AI}, 10",
		"${ExampleNames.CERVENY_UJEZD_SYNC}, 8",
		"${ExampleNames.PRAHA_HLAVNI_NADRAZI}, 4",
		"${ExampleNames.PRAHA_HLAVNI_NADRAZI_AI}, 4",
		"${ExampleNames.MULTI_TRAIN_LOOP}, 2",
		"${ExampleNames.THREE_TRAIN_LOOP}, 2"
	)
	fun `each sweepable example has its measured bar`(
		example: String,
		bar: Long
	) {
		assertThat(ExitPassBars.barFor(example)).isEqualTo(bar)
	}

	@ParameterizedTest(name = "{0} uses the bar of {1}")
	@CsvSource(
		"${ExampleNames.SHUNTING_LOOP_AI}, ${ExampleNames.SHUNTING_LOOP}",
		"${ExampleNames.CERVENY_UJEZD_AI}, ${ExampleNames.CERVENY_UJEZD}",
		"${ExampleNames.PRAHA_HLAVNI_NADRAZI_AI}, ${ExampleNames.PRAHA_HLAVNI_NADRAZI}"
	)
	fun `an LLM example uses the bar and the source of its control-arm twin`(
		llmExample: String,
		controlArm: String
	) {
		assertThat(ExitPassBars.entryFor(llmExample)).isEqualTo(ExitPassBars.entryFor(controlArm))
	}

	@Test
	fun `a file without an example keeps the vyhybna bar of issue 895`() {
		assertThat(ExitPassBars.barFor(null)).isEqualTo(RunReportAggregator.MIN_TRAINS_EXITED)
		assertThat(RunReportAggregator.MIN_TRAINS_EXITED).isEqualTo(6L)
	}

	/** A blank example is no example, as in the sweep cell key (Issue #1333): its row reads `(unnamed)`. */
	@ParameterizedTest
	@ValueSource(strings = ["", " ", "\t"])
	fun `a blank example reads as unnamed and keeps the vyhybna bar`(example: String) {
		assertThat(ExitPassBars.barFor(example)).isEqualTo(RunReportAggregator.MIN_TRAINS_EXITED)
	}

	@ParameterizedTest
	@ValueSource(strings = ["vyhybna.xml", "cerveny_ujezd.xml", "noSuchExample"])
	fun `an unknown example name has no bar`(example: String) {
		assertThat(ExitPassBars.barFor(example)).isNull()
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			ExampleNames.SHUNTING_LOOP, ExampleNames.SHUNTING_LOOP_SYNC, ExampleNames.CERVENY_UJEZD,
			ExampleNames.CERVENY_UJEZD_SYNC, ExampleNames.PRAHA_HLAVNI_NADRAZI, ExampleNames.MULTI_TRAIN_LOOP,
			ExampleNames.THREE_TRAIN_LOOP
		]
	)
	fun `every bar carries its control-arm source at the 600 s horizon`(example: String) {
		assertThat(ExitPassBars.entryFor(example)).isNotNull().prop(ExitPassBar::source).all {
			prop(ExitBarSource::horizonSeconds).isEqualTo(ExitPassBars.CONTROL_ARM_HORIZON_SECONDS)
			prop(ExitBarSource::runCount).isGreaterThan(0)
			prop(ExitBarSource::provenance).isNotEmpty()
			prop(ExitBarSource::rawFigures).isNotEmpty()
		}
	}

	/**
	 * Tripwire (Issue #1315 review): a new example constant must get a measured bar here or be
	 * listed in [EXCLUDED_FROM_EXIT_BARS] on purpose, so it cannot fall into "not measured" silently.
	 */
	@Test
	fun `every ExampleNames constant has an exit bar or is excluded on purpose`() {
		val names =
			ExampleNames::class.java.declaredFields
				.filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
				.map { it.get(null) as String }
		assertThat(names.size, "constants found").isGreaterThan(0)

		val missing = names.filter { ExitPassBars.entryFor(it) == null && it !in EXCLUDED_FROM_EXIT_BARS }
		assertThat(missing, "examples without a bar").isEmpty()
	}

	private companion object {
		/** Example names that deliberately have no exit bar. None today. */
		val EXCLUDED_FROM_EXIT_BARS: Set<String> = emptySet()
	}
}
