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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

/**
 * Tests for [currentSimulationTime] and [currentSimulationTimeOrNull]: outside a running simulation
 * the caller-chosen fallback is returned (issue #701 folded the NaN and 0.0 variants into this one
 * helper), or `null` instead (issue #1421, PR #1430 review -- the GUI clock publishes a paced gap
 * only with an authoritative timestamp).
 */
class SimulationTimeTest {
	@Test
	fun outsideRunDefaultFallbackIsZero() {
		assertThat(currentSimulationTime()).isEqualTo(0.0)
	}

	@Test
	fun outsideRunNanFallbackIsNan() {
		assertThat(currentSimulationTime(Double.NaN).isNaN()).isTrue()
	}

	@Test
	fun outsideRunExplicitFallbackIsReturnedVerbatim() {
		assertThat(currentSimulationTime(-1.5)).isEqualTo(-1.5)
	}

	@Test
	fun outsideRunVersionReturnsNull() {
		assertThat(currentSimulationTimeOrNull()).isNull()
	}
}
