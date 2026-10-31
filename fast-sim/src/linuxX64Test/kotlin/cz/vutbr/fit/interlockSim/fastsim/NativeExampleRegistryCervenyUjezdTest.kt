/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP3.2 (Issue #1164): the Cerveny Ujezd scenario runs by name on the native path.
 */
package cz.vutbr.fit.interlockSim.fastsim
import cz.vutbr.fit.interlockSim.ExampleNames
import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `NativeExampleRegistry` resolves `cervenyUjezd` and runs it through the same construction as
 * `fast-sim example cervenyUjezd <endTime>` (the CLI maps a clean run to exit code 0).
 */
class NativeExampleRegistryCervenyUjezdTest {
	private companion object {
		const val END_TIME: Long = 600L
		const val CAPACITY = 4
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
	fun `the registry lists cervenyUjezd next to shuntingLoop`() {
		// The strings come from `ExampleNames` (`:core`) — both registries share the one home.
		assertTrue(
			ExampleNames.CERVENY_UJEZD in NativeExampleRegistry.AVAILABLE,
			"available: ${NativeExampleRegistry.AVAILABLE}"
		)
		assertTrue(
			ExampleNames.SHUNTING_LOOP in NativeExampleRegistry.AVAILABLE,
			"available: ${NativeExampleRegistry.AVAILABLE}"
		)
	}

	@Test
	fun `cervenyUjezd runs by name on the four-track station and trains get through`() {
		NativeExampleRegistry.create(ExampleNames.CERVENY_UJEZD, END_TIME, NativeContextFactory()).use { ctx ->
			val loop = ctx.mainProcess as ShuntingLoop
			assertEquals(CAPACITY, loop.stationCapacity, "capacity comes from the four station tracks")

			ctx.run()

			assertTrue(loop.getTrainsEntered() > 0, "no train entered")
			assertTrue(loop.getTrainsExited() > 0, "no train exited")
			assertTrue(loop.getTrainsExited() <= loop.getTrainsEntered(), "more exits than entries")
		}
	}
}
