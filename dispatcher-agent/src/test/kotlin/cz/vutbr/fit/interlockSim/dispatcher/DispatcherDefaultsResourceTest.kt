/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [DispatcherDefaultsResource] (Issue #834, SP2c.11).
 *
 * These tests construct in-memory streams rather than touching the real classpath resource, so
 * they can exercise "absent" and "malformed" failure modes that the shipped file (by construction)
 * never exhibits. The one exception is the last test, which is the regression lock for #834's
 * "no default's value changes" requirement: it reads the real shipped resource.
 */
@DisplayName("SP2c.11 — DispatcherDefaultsResource: committed-file tier loader (#834)")
class DispatcherDefaultsResourceTest {
	private fun streamOf(text: String) = ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))

	@Test
	@DisplayName("absent resource (null stream) falls back to no values, no exception")
	fun absentStreamYieldsNoValues() {
		val resource = DispatcherDefaultsResource.fromStream(null)

		for (key in DispatcherDefaultsResource.RECOGNIZED_KEYS) {
			assertThat(resource.lookup(key)).isNull()
		}
	}

	@Test
	@DisplayName("malformed properties syntax falls back to no values, no exception")
	fun malformedStreamYieldsNoValues() {
		// An invalid \u escape makes java.util.Properties#load throw IOException.
		val resource = DispatcherDefaultsResource.fromStream(streamOf("interlocksim.dispatcher.historyN=\\uZZZZ"))

		assertThat(resource.lookup(DispatcherRunConfig.PROP_HISTORY_N)).isNull()
	}

	@Test
	@DisplayName("a recognized key is exposed via lookup")
	fun recognizedKeyIsExposed() {
		val resource = DispatcherDefaultsResource.fromStream(streamOf("interlocksim.dispatcher.historyN=7"))

		assertThat(resource.lookup(DispatcherRunConfig.PROP_HISTORY_N)).isEqualTo("7")
	}

	@Test
	@DisplayName("an unknown key is ignored, not exposed, and does not break parsing of known keys")
	fun unknownKeyIsIgnored() {
		val resource =
			DispatcherDefaultsResource.fromStream(
				streamOf(
					"""
					interlocksim.dispatcher.historyN=7
					interlocksim.dispatcher.historyNTypo=99
					""".trimIndent()
				)
			)

		assertThat(resource.lookup(DispatcherRunConfig.PROP_HISTORY_N)).isEqualTo("7")
		assertThat(resource.lookup("interlocksim.dispatcher.historyNTypo")).isNull()
	}

	@Test
	@DisplayName("the shipped resource yields exactly today's compiled defaults, unchanged (#834 regression lock)")
	fun shippedResourceMatchesCompiledDefaults() {
		val shipped = DispatcherDefaultsResource.shipped

		assertThat(shipped.lookup(DispatcherRunConfig.PROP_TICK_PERIOD_MS)).isEqualTo("0")
		assertThat(shipped.lookup(DispatcherRunConfig.PROP_HISTORY_N)).isEqualTo("3")
		assertThat(shipped.lookup(DispatcherRunConfig.PROP_MAX_ACTIONS_PER_TICK)).isEqualTo("3")
		assertThat(shipped.lookup(DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS)).isEqualTo("30")
		assertThat(shipped.lookup(DispatcherRunConfig.PROP_MODEL)).isEqualTo("qwen2.5:7b-instruct")
		assertThat(shipped.lookup(DispatcherRunConfig.PROP_TEMPERATURE)).isEqualTo("0.28")
	}
}
