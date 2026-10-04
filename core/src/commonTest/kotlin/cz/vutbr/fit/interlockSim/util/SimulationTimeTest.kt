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
import assertk.assertions.isTrue
import kotlin.test.Test

/**
 * Tests for [currentSimulationTime]: outside a running simulation the caller-chosen fallback is
 * returned (issue #701 folded the NaN and 0.0 variants into this one helper).
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
}
