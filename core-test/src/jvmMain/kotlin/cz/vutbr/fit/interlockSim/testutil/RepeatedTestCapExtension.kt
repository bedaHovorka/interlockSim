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

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.commons.support.AnnotationSupport
import org.opentest4j.TestAbortedException

/**
 * Enforces the `@RepeatedTest` repetition caps (Issue #1110).
 *
 * JUnit Jupiter has no setting that limits `@RepeatedTest(n)`, so this extension does it. The cap
 * follows the test's tags, not the Gradle task: a test tagged [HEAVY_TAG] (class or method level)
 * may repeat up to [HEAVY_CAP] times, every other test up to [DEFAULT_CAP] times. The
 * `excludeTags("heavy-test")` filter of `test`/`integrationTest` keeps heavy tests out of CI, so
 * the default cap is the CI cap.
 *
 * Above the cap the test fails before its body — and before any `@BeforeEach` method — runs. Only
 * the first repetition is reported as a failure; the others are aborted (reported as skipped), so
 * an untagged `@RepeatedTest(1000)` yields one clear failure instead of a thousand.
 *
 * Registered through `META-INF/services/org.junit.jupiter.api.extension.Extension`; active when
 * `junit.jupiter.extensions.autodetection.enabled=true`, which the root build script sets on every
 * `Test` task.
 */
class RepeatedTestCapExtension : BeforeEachCallback {
	override fun beforeEach(context: ExtensionContext) {
		val repeated =
			AnnotationSupport
				.findAnnotation(context.requiredTestMethod, RepeatedTest::class.java)
				.orElse(null) ?: return
		val cap = if (HEAVY_TAG in context.tags) HEAVY_CAP else DEFAULT_CAP
		if (repeated.value <= cap) return

		val message =
			"@RepeatedTest(${repeated.value}) on ${context.requiredTestClass.name}." +
				"${context.requiredTestMethod.name} exceeds the repetition cap of $cap. Lower the " +
				"repetition count, or tag the test @Tag(\"$HEAVY_TAG\") (cap $HEAVY_CAP) so it runs " +
				"only in heavyTest."
		val templateStore = context.parent.orElse(context).getStore(NAMESPACE)
		if (templateStore.get(REPORTED_KEY) != null) {
			throw TestAbortedException("$message (already reported on the first repetition)")
		}
		templateStore.put(REPORTED_KEY, true)
		throw AssertionError(message)
	}

	companion object {
		/** Cap for every test without the [HEAVY_TAG] tag — the `test` and `integrationTest` tasks. */
		const val DEFAULT_CAP = 50

		/** Cap for tests tagged [HEAVY_TAG] — the manual-only `heavyTest` task. */
		const val HEAVY_CAP = 1000

		const val HEAVY_TAG = "heavy-test"

		private const val REPORTED_KEY = "capViolationReported"
		private val NAMESPACE = ExtensionContext.Namespace.create(RepeatedTestCapExtension::class.java)
	}
}
