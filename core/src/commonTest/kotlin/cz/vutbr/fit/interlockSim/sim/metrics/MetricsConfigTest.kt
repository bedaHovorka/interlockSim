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
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

/**
 * Unit tests for [MetricsConfig] and [MetricGroup].
 *
 * @since Issue #674 (Goal 6 SP4)
 */
class MetricsConfigTest {
	@Test
	fun `default config enables every metric group`() {
		MetricGroup.entries.forEach { group ->
			assertThat(MetricsConfig.ALL.isEnabled(group)).isTrue()
		}
		assertThat(MetricsConfig.ALL.enabled).isEqualTo(MetricGroup.entries.toSet())
	}

	@Test
	fun `core only enables nothing but CORE`() {
		assertThat(MetricsConfig.coreOnly().enabled).containsOnly(MetricGroup.CORE)
		assertThat(MetricsConfig.coreOnly().isEnabled(MetricGroup.HISTORY)).isFalse()
	}

	@Test
	fun `CORE stays on even when the caller omits it`() {
		val config = MetricsConfig(setOf(MetricGroup.HISTORY))

		assertThat(config.isEnabled(MetricGroup.CORE)).isTrue()
		assertThat(config.isEnabled(MetricGroup.HISTORY)).isTrue()
		assertThat(config.isEnabled(MetricGroup.JOURNEYS)).isFalse()
		assertThat(MetricsConfig(emptySet())).isEqualTo(MetricsConfig.coreOnly())
	}
}
