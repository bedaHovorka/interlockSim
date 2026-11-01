/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import cz.vutbr.fit.interlockSim.ExampleNames
import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A native run produces the run record of `:core` and the CLI prints it (Issue #676, Goal 6 SP5),
 * at run end and in the SIGINT partial summary, through [runRecordLine].
 *
 * @since Issue #676 (Goal 6 SP5)
 */
class NativeRunRecordTest {
	private companion object {
		const val END_TIME: Long = 600L
	}

	private var previousLogLevel: Level = KotlinLoggingConfiguration.logLevel

	@BeforeTest
	fun setUp() {
		previousLogLevel = KotlinLoggingConfiguration.logLevel
		KotlinLoggingConfiguration.logLevel = Level.WARN
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
		KotlinLoggingConfiguration.logLevel = previousLogLevel
	}

	@Test
	fun `a native cervenyUjezd run produces its record and the CLI line shows it`() {
		NativeExampleRegistry.create(ExampleNames.CERVENY_UJEZD, END_TIME, NativeContextFactory()).use { ctx ->
			ctx.run()

			val record = ctx.getMetricsServices().getRunRecordSource().runRecord()
			assertNotNull(record, "run record")
			assertEquals(RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS, record.classification)
			assertTrue(record.trainsEntered > 0, "trains entered: ${record.trainsEntered}")
			assertEquals(record.summaryLine(), runRecordLine(ctx))
		}
	}

	@Test
	fun `before the run ended the CLI line says the record is not available`() {
		NativeExampleRegistry.create(ExampleNames.SHUNTING_LOOP, END_TIME, NativeContextFactory()).use { ctx ->
			val line = runRecordLine(ctx)

			assertTrue(line.startsWith("Run record: not available"), line)
		}
	}
}
