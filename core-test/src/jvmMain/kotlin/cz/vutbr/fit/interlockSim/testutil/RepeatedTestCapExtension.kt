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
 * may repeat up to the heavy cap, every other test up to the default cap. The
 * `excludeTags("heavy-test")` filter of `test`/`integrationTest` keeps heavy tests out of CI, so
 * the default cap is the CI cap.
 *
 * The cap values come from gradle.properties (`testRepeatMaxCount`, `heavyTestRepeatMaxCount`);
 * the root build script passes them to every `Test` task as the `interlockSim.test.repeat.*`
 * system properties. [DEFAULT_CAP] and [HEAVY_CAP] are the fallbacks when a property is absent,
 * and the read is lazy — [defaultCap]/[heavyCap] see an override set at any time before the
 * repetition starts.
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
		val cap = if (HEAVY_TAG in context.tags) heavyCap() else defaultCap()
		if (repeated.value <= cap) return

		val message =
			"@RepeatedTest(${repeated.value}) on ${context.requiredTestClass.name}." +
				"${context.requiredTestMethod.name} exceeds the repetition cap of $cap. Lower the " +
				"repetition count, or tag the test @Tag(\"$HEAVY_TAG\") (cap ${heavyCap()}) so it " +
				"runs only in heavyTest."
		val templateStore = context.parent.orElse(context).getStore(NAMESPACE)
		if (templateStore.get(REPORTED_KEY) != null) {
			throw TestAbortedException("$message (already reported on the first repetition)")
		}
		templateStore.put(REPORTED_KEY, true)
		throw AssertionError(message)
	}

	companion object {
		/** Fallback cap for every test without the [HEAVY_TAG] tag — the `test`/`integrationTest` tasks. */
		const val DEFAULT_CAP = 50

		/** Fallback cap for tests tagged [HEAVY_TAG] — the manual-only `heavyTest` task. */
		const val HEAVY_CAP = 1000

		const val HEAVY_TAG = "heavy-test"

		/** System property the root build script fills from gradle.properties `testRepeatMaxCount`. */
		const val DEFAULT_CAP_PROPERTY = "interlockSim.test.repeat.maxCount"

		/** System property the root build script fills from gradle.properties `heavyTestRepeatMaxCount`. */
		const val HEAVY_CAP_PROPERTY = "interlockSim.test.repeat.heavyMaxCount"

		private const val REPORTED_KEY = "capViolationReported"
		private val NAMESPACE = ExtensionContext.Namespace.create(RepeatedTestCapExtension::class.java)

		/** The default cap: gradle.properties `testRepeatMaxCount`, read lazily. */
		fun defaultCap(): Int = System.getProperty(DEFAULT_CAP_PROPERTY)?.toIntOrNull() ?: DEFAULT_CAP

		/** The heavy cap: gradle.properties `heavyTestRepeatMaxCount`, read lazily. */
		fun heavyCap(): Int = System.getProperty(HEAVY_CAP_PROPERTY)?.toIntOrNull() ?: HEAVY_CAP
	}
}