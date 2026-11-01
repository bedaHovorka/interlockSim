/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins [recordGeometricFailureOnce], the shared geometric-failure recorder (Issue #1210, SP12.9):
 * the first recorded reason wins (Issue #903) and every recorded hit increments the count
 * (Issue #937).
 *
 * @since Issue #1210
 */
@DisplayName("Geometric failure recorder (Issue #1210)")
class GeometricFailuresTest {
	@Test
	fun `the first reason wins and every recorded failure increments the count`() {
		var failures = recordGeometricFailureOnce(GeometricFailures(), "switch locked in another position")
		failures = recordGeometricFailureOnce(failures, "route end faces away")
		failures = recordGeometricFailureOnce(failures, "route end faces away")

		assertThat(failures)
			.isEqualTo(GeometricFailures(reason = "switch locked in another position", count = 3))
	}
}
