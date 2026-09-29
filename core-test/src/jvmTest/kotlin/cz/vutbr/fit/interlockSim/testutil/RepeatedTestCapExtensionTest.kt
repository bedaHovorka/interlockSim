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
import cz.vutbr.fit.interlockSim.testutil.RepeatedTestCapExtension.Companion.DEFAULT_CAP
import cz.vutbr.fit.interlockSim.testutil.RepeatedTestCapExtension.Companion.DEFAULT_CAP_PROPERTY
import cz.vutbr.fit.interlockSim.testutil.RepeatedTestCapExtension.Companion.HEAVY_CAP
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.junit.platform.testkit.engine.Events
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves [RepeatedTestCapExtension] (Issue #1110). The fixtures below run through EngineTestKit
 * with the same system properties as this Test task, so the tests also prove that the root build
 * script turns on extension auto-detection and that the `META-INF/services` file is on the
 * classpath. The Test task excludes the `cap-fixture` tag, so it never runs the fixtures itself.
 */
class RepeatedTestCapExtensionTest {
	@Test
	fun `untagged test above the default cap fails once and never runs its body`() {
		val events = run(UntaggedOverCap::class.java)

		events.assertStatistics { it.failed(1).aborted(DEFAULT_CAP.toLong()).succeeded(0) }
		assertThat(UntaggedOverCap.executions.get()).isEqualTo(0)
	}

	@Test
	fun `untagged test at the default cap runs every repetition`() {
		run(UntaggedAtCap::class.java).assertStatistics { it.succeeded(DEFAULT_CAP.toLong()).failed(0) }
	}

	@Test
	fun `heavy-test tag lifts the cap to the heavy cap`() {
		run(HeavyAboveDefaultCap::class.java).assertStatistics { it.succeeded(DEFAULT_CAP + 1L).failed(0) }
	}

	@Test
	fun `heavy test at the heavy cap runs every repetition`() {
		run(HeavyAtCap::class.java).assertStatistics { it.succeeded(HEAVY_CAP.toLong()).failed(0) }
	}

	@Test
	fun `heavy test above the heavy cap fails`() {
		run(HeavyOverCap::class.java).assertStatistics { it.failed(1).aborted(HEAVY_CAP.toLong()).succeeded(0) }
	}

	@Test
	fun `two over-cap methods in one class each fail exactly once`() {
		val events = run(TwoOverCapMethods::class.java)

		// One failure per method, and every further repetition of both methods aborted — the
		// report-once store is per method, not per class.
		events.assertStatistics { it.failed(2).aborted(2 * DEFAULT_CAP.toLong()).succeeded(0) }
		assertThat(TwoOverCapMethods.executions.get()).isEqualTo(0)
	}

	@Test
	fun `over-cap test never runs its BeforeEach methods`() {
		run(OverCapWithBeforeEach::class.java).assertStatistics { it.failed(1).aborted(DEFAULT_CAP.toLong()) }

		assertThat(OverCapWithBeforeEach.beforeEachRuns.get()).isEqualTo(0)
	}

	@Test
	fun `failure message names the cap and says how to lift it`() {
		val events = run(UntaggedOverCap::class.java)

		val result = events.failed().list().single().getRequiredPayload(TestExecutionResult::class.java)
		val message = result.throwable.orElseThrow().message
		assertThat(message).isNotNull().contains(
			"exceeds the repetition cap of ${RepeatedTestCapExtension.defaultCap()}",
			"@Tag(\"${RepeatedTestCapExtension.HEAVY_TAG}\")",
		)
	}

	@Test
	fun `cap override through the system property takes effect`() {
		val original = System.getProperty(DEFAULT_CAP_PROPERTY)
		System.setProperty(DEFAULT_CAP_PROPERTY, "2")
		try {
			// The extension reads the property lazily, so the override applies mid-run.
			run(OverrideAtCap::class.java).assertStatistics { it.failed(1).aborted(2L).succeeded(0) }
			assertThat(OverrideAtCap.executions.get()).isEqualTo(0)
		} finally {
			if (original == null) {
				System.clearProperty(DEFAULT_CAP_PROPERTY)
			} else {
				System.setProperty(DEFAULT_CAP_PROPERTY, original)
			}
		}
	}

	private fun run(fixture: Class<*>): Events =
		EngineTestKit
			.engine("junit-jupiter")
			.selectors(selectClass(fixture))
			// EngineTestKit ignores system properties by default; read them, so the fixtures see
			// the auto-detection flag of this Test task.
			.enableImplicitConfigurationParameters(true)
			.execute()
			.testEvents()

	@Tag("cap-fixture")
	class UntaggedOverCap {
		@RepeatedTest(DEFAULT_CAP + 1)
		fun overCap() {
			executions.incrementAndGet()
		}

		companion object {
			val executions = AtomicInteger()
		}
	}

	@Tag("cap-fixture")
	class UntaggedAtCap {
		@RepeatedTest(DEFAULT_CAP)
		fun atCap() {
			// Only the repetition count matters.
		}
	}

	// Class-level tag: the extension must see tags inherited from the class.
	@Tag("cap-fixture")
	@Tag("heavy-test")
	class HeavyAboveDefaultCap {
		@RepeatedTest(DEFAULT_CAP + 1)
		fun aboveDefaultCap() {
			// Only the repetition count matters.
		}
	}

	// The boundary the real heavy tests sit on: exactly at the heavy cap, so it must pass.
	@Tag("cap-fixture")
	@Tag("heavy-test")
	class HeavyAtCap {
		@RepeatedTest(HEAVY_CAP)
		fun atCap() {
			// Only the repetition count matters.
		}
	}

	@Tag("cap-fixture")
	class HeavyOverCap {
		@Tag("heavy-test")
		@RepeatedTest(HEAVY_CAP + 1)
		fun overCap() {
			// Never runs: the extension fails every repetition before the body.
		}
	}

	// Two violations in one class: the first-repetition-only failure must be per method.
	@Tag("cap-fixture")
	class TwoOverCapMethods {
		@RepeatedTest(DEFAULT_CAP + 1)
		fun firstOverCap() {
			executions.incrementAndGet()
		}

		@RepeatedTest(DEFAULT_CAP + 1)
		fun secondOverCap() {
			executions.incrementAndGet()
		}

		companion object {
			val executions = AtomicInteger()
		}
	}

	// The extension must fail before any @BeforeEach method runs (its BeforeEachCallback runs first).
	@Tag("cap-fixture")
	class OverCapWithBeforeEach {
		@BeforeEach
		fun setUp() {
			beforeEachRuns.incrementAndGet()
		}

		@RepeatedTest(DEFAULT_CAP + 1)
		fun overCap() {
			// Never runs.
		}

		companion object {
			val beforeEachRuns = AtomicInteger()
		}
	}

	// 3 repetitions against an overridden cap of 2: fails once, aborts twice.
	@Tag("cap-fixture")
	class OverrideAtCap {
		@RepeatedTest(3)
		fun overCap() {
			executions.incrementAndGet()
		}

		companion object {
			val executions = AtomicInteger()
		}
	}
}
