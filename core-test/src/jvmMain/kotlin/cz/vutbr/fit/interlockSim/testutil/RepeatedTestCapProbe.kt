/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.RepeatedTest
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.util.concurrent.atomic.AtomicInteger

/** Repetition cap of the `test` and `integrationTest` tasks (`testRepeatMaxCount` in gradle.properties). */
const val TEST_TASK_REPEAT_CAP = 50

/**
 * Fixture run only through [RepeatedTestCapProbe]: one repetition above [TEST_TASK_REPEAT_CAP].
 * Lives in `:core-test` main code, so no module's Test task discovers it on its own.
 */
class RepeatedTestOverCapFixture {
	@RepeatedTest(TEST_TASK_REPEAT_CAP + 1)
	fun overCap() {
		executions.incrementAndGet()
	}

	companion object {
		val executions = AtomicInteger()
	}
}

/** Fixture run only through [RepeatedTestCapProbe]: exactly [TEST_TASK_REPEAT_CAP] repetitions. */
class RepeatedTestAtCapFixture {
	@RepeatedTest(TEST_TASK_REPEAT_CAP)
	fun atCap() {
		executions.incrementAndGet()
	}

	companion object {
		val executions = AtomicInteger()
	}
}

/**
 * Proves that the calling Gradle `Test` task enforces the `@RepeatedTest` cap (Issue #1110).
 *
 * Runs the fixtures through a nested JUnit Platform launcher that inherits the Test task's JVM
 * system properties — the cap ([RepeatedTestCapExtension.MAX_COUNT_KEY]) and extension
 * auto-detection — exactly as an ordinary test class in that task would see them. Each module
 * calls [assertCapEnforced] from an untagged test (the `test` task) and from an
 * `integration-test`-tagged test (the `integrationTest` task).
 */
object RepeatedTestCapProbe {
	private const val AUTODETECTION_KEY = "junit.jupiter.extensions.autodetection.enabled"

	fun assertCapEnforced() {
		assertThat(System.getProperty(RepeatedTestCapExtension.MAX_COUNT_KEY), "cap system property")
			.isNotNull()
		assertThat(System.getProperty(AUTODETECTION_KEY), "extension auto-detection").isEqualTo("true")

		RepeatedTestOverCapFixture.executions.set(0)
		val overCap = execute(RepeatedTestOverCapFixture::class.java)
		assertThat(overCap.testsFailedCount, "over-cap failures").isEqualTo(1L)
		assertThat(overCap.testsAbortedCount, "over-cap aborted repetitions")
			.isEqualTo(TEST_TASK_REPEAT_CAP.toLong())
		assertThat(overCap.testsSucceededCount, "over-cap successes").isEqualTo(0L)
		assertThat(RepeatedTestOverCapFixture.executions.get(), "over-cap body executions").isEqualTo(0)
		assertThat(
			overCap.failures
				.single()
				.exception.message
				.orEmpty()
		).contains("@RepeatedTest(${TEST_TASK_REPEAT_CAP + 1})")

		RepeatedTestAtCapFixture.executions.set(0)
		val atCap = execute(RepeatedTestAtCapFixture::class.java)
		assertThat(atCap.testsFailedCount, "at-cap failures").isEqualTo(0L)
		assertThat(atCap.testsSucceededCount, "at-cap successes").isEqualTo(TEST_TASK_REPEAT_CAP.toLong())
		assertThat(RepeatedTestAtCapFixture.executions.get(), "at-cap body executions")
			.isEqualTo(TEST_TASK_REPEAT_CAP)
	}

	private fun execute(fixture: Class<*>) =
		SummaryGeneratingListener()
			.also { listener ->
				val request =
					LauncherDiscoveryRequestBuilder
						.request()
						.selectors(DiscoverySelectors.selectClass(fixture))
						.build()
				LauncherFactory.create().execute(request, listener)
			}.summary
}
