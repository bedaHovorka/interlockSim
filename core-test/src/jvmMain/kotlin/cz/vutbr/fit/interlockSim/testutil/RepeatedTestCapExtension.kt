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
 * Enforces the per-Test-task `@RepeatedTest` repetition cap (Issue #1110).
 *
 * JUnit Jupiter itself has no configuration parameter that limits `@RepeatedTest(n)`, so this
 * extension does it: when the [MAX_COUNT_KEY] configuration parameter (a JVM system property
 * set by each Gradle `Test` task, or an entry in `junit-platform.properties`) is present and a
 * test method's `@RepeatedTest.value` exceeds it, the test fails before its body — and before
 * any `@BeforeEach` method — runs. Only the first repetition is reported as a failure; the
 * remaining ones are aborted (reported as skipped), so a mistagged 1000-repetition heavy test
 * yields one clear failure instead of a thousand.
 *
 * Registered through `META-INF/services/org.junit.jupiter.api.extension.Extension`; active only
 * when `junit.jupiter.extensions.autodetection.enabled=true`. Without the [MAX_COUNT_KEY]
 * parameter (e.g. a plain IDE run) the extension does nothing.
 */
class RepeatedTestCapExtension : BeforeEachCallback {
	override fun beforeEach(context: ExtensionContext) {
		val rawCap = context.getConfigurationParameter(MAX_COUNT_KEY).orElse(null) ?: return
		val cap =
			requireNotNull(rawCap.trim().toIntOrNull()) {
				"Configuration parameter $MAX_COUNT_KEY must be an integer, was '$rawCap'"
			}
		val repeated =
			AnnotationSupport
				.findAnnotation(context.requiredTestMethod, RepeatedTest::class.java)
				.orElse(null) ?: return
		if (repeated.value <= cap) return

		val message =
			"@RepeatedTest(${repeated.value}) on ${context.requiredTestClass.name}." +
				"${context.requiredTestMethod.name} exceeds the repetition cap of $cap " +
				"($MAX_COUNT_KEY) of this Test task. Lower the repetition count, or tag the test " +
				"@Tag(\"heavy-test\") so it runs only in heavyTest."
		val templateStore = context.parent.orElse(context).getStore(NAMESPACE)
		if (templateStore.get(REPORTED_KEY) != null) {
			throw TestAbortedException("$message (already reported on the first repetition)")
		}
		templateStore.put(REPORTED_KEY, true)
		throw AssertionError(message)
	}

	companion object {
		/** Configuration parameter holding the maximum allowed `@RepeatedTest` value. */
		const val MAX_COUNT_KEY = "interlockSim.test.repeat.maxCount"

		private const val REPORTED_KEY = "capViolationReported"
		private val NAMESPACE = ExtensionContext.Namespace.create(RepeatedTestCapExtension::class.java)
	}
}
