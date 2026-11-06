/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test of the ArrivalTally test utility (PR #1267 review round: the Cerveny Ujezd determinism
 * test reads emission-ordered arrivals through ArrivalTally.arrivalOrder).
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ArrivalTallyTest {
	/** Numbers arrive in emission order; a later repeat of a number does not disturb the order. */
	@Test
	fun `arrivalOrder records numbers at their first sighting`() {
		val arrivals = ArrivalTally()

		arrivals.record("12.0 Train #3 ends")
		arrivals.record("14.5 Train #1 ends")
		arrivals.record("15.0 Train #3 ends") // repeated report: order frozen, count grows

		assertThat(arrivals.arrivalOrder).containsExactly(3, 1)
		assertThat(arrivals.arrivalOrder).containsExactly(3, 1)
		assertThat(arrivals.arrivedTrainNumbers).isEqualTo(setOf(3, 1))
		assertThat(arrivals.count).isEqualTo(3)
	}

	/** Only completed-journey reports (ending in the `ends` suffix with a train number) count. */
	@Test
	fun `record ignores every message that is not a completed journey`() {
		val arrivals = ArrivalTally()

		arrivals.record("12.0 Train #2 approved")
		arrivals.record("12.0 approved Train #2")
		arrivals.record("12.0 something ends")
		arrivals.record("12.0 Train #nope ends")
		arrivals.record("12.0 Train #2 ends")

		assertThat(arrivals.arrivalOrder).containsExactly(2)
		assertThat(arrivals.count).isEqualTo(1)
	}
}
