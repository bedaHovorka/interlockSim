/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Pins how the core [RunEndClassification] maps onto the persisted [RunEndCause] (Issue #1304,
 * Goal 6 SP6). The cause is not replaced: a cutoff still records as a natural completion, as such a
 * run did before, and a stall records as an early termination (its queue stopped before the end
 * time).
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
class RunEndClassificationMappingTest {
	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(
		"NATURAL_COMPLETION, NATURAL_COMPLETION",
		"CUTOFF_WITH_UNFINISHED_JOURNEYS, NATURAL_COMPLETION",
		"STALLED, TERMINATED_EARLY",
		"TERMINATED_EARLY, TERMINATED_EARLY"
	)
	fun `every classification maps onto its end cause`(
		classification: RunEndClassification,
		cause: RunEndCause
	) {
		assertThat(classification.toRunEndCause()).isEqualTo(cause)
	}
}
