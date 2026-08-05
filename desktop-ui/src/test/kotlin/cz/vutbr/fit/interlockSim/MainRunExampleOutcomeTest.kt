/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Proves the headless `example` mode reports how a run ended (Issue #847 round 3, PR #891 defect C).
 *
 * `Main.runExample` used to return `Unit` and swallow every failure into a `logger.error`, and
 * `main` never called `exitProcess`, so the JVM exited 0 for a completed run, a deadlocked run, a
 * CRITICAL collision and a swallowed exception alike. Round 2's third verification run ended at
 * simTime 72.0 s of a requested 600 s and still exited 0 — and #847's unattended sweep would have
 * recorded it as a valid data point.
 *
 * The non-AI `shuntingLoop` example is used deliberately: it is rule-based, needs no Ollama, and
 * runs to completion in about a second of wall clock, so it is a legitimate unit test of the
 * outcome plumbing rather than of the LLM.
 *
 * @since Issue #847 (round 3)
 */
@DisplayName("Main.runExample reports whether the run reached its requested end time")
class MainRunExampleOutcomeTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a run that reaches its end time reports COMPLETED")
	fun completedRunReportsCompleted() {
		val outcome = get<Main>().runExample(arrayOf("example", "shuntingLoop", "30"))

		assertThat(outcome).isEqualTo(RunOutcome.COMPLETED)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("an unknown example name reports NOT_STARTED, which is not a deadlock")
	fun unknownExampleReportsNotStarted() {
		// Nothing ran, so this must not be reported as success — but it must not be reported as a
		// deadlock either, or #847's sweep would score a typo as a failed dispatch run. It is also
		// why `main` does not exit on NOT_STARTED: this is the usage-error path.
		val outcome = get<Main>().runExample(arrayOf("example", "no-such-example", "30"))

		assertThat(outcome).isEqualTo(RunOutcome.NOT_STARTED)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a missing end-time argument reports NOT_STARTED")
	fun missingEndTimeReportsNotStarted() {
		val outcome = get<Main>().runExample(arrayOf("example", "shuntingLoop"))

		assertThat(outcome).isEqualTo(RunOutcome.NOT_STARTED)
	}
}
