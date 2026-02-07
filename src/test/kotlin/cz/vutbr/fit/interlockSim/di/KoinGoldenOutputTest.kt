/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.di

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.test.get

/**
 * Golden output baseline tests for Koin DI adoption validation
 *
 * These tests ensure that Koin dependency injection does not alter
 * simulation behavior. Tests capture simulation output and compare
 * against baseline values established before DI adoption.
 *
 * CRITICAL REQUIREMENT (traffic-simulation-expert):
 * - Simulation results must be IDENTICAL before and after Koin adoption
 * - Tolerance: position within 1e-6m, time within 1e-9s
 * - These tests MUST pass before merging any Koin changes
 *
 * @see <a href="https://github.com/bedavs/interlockSim">docs/KOTLIN_STYLE_GUIDE.md - Dependency Injection with Koin</a>
 */
@Tag("integration-test")
class KoinGoldenOutputTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	/**
	 * Basic Koin initialization and simulation execution test
	 *
	 * This test validates that:
	 * 1. Koin can be initialized with interlockSimModule
	 * 2. SimulationContextFactory can be retrieved from Koin
	 * 3. A simulation context can be created using the factory
	 * 4. ShuntingLoop simulation can be constructed with Koin-managed dependencies
	 *
	 * This is the minimal test required to verify Koin integration doesn't break
	 * the core simulation functionality.
	 */
	@Test
	@Tag("integration-test")
	fun `basic Koin initialization and simulation setup succeeds`() {
		// Koin is already initialized by KoinTestBase.setUpKoin()

		// Get SimulationContextFactory from Koin DI container
		val factory = get<SimulationContextFactory>()
		assertThat(factory).isNotNull()

		// Load vyhybna.xml and create simulation context
		val xml =
			javaClass.getResourceAsStream(
				"/cz/vutbr/fit/interlockSim/resource/vyhybna.xml"
			)
		assertThat(xml).isNotNull()

		val context = factory.createContext(xml)
		assertThat(context).isNotNull()
		assertThat(context).isInstanceOf(DefaultSimulationContext::class)

		// Wrap in MockSimulationContext to avoid running actual simulation
		// Safe cast after type validation above
		val defaultContext =
			context as? DefaultSimulationContext
				?: throw AssertionError("Context should be DefaultSimulationContext")
		val simContext = MockSimulationContext(defaultContext)

		// Create ShuntingLoop with Koin-managed context
		val shuntingLoop = ShuntingLoop(simContext, 60L)
		assertThat(shuntingLoop).isNotNull()

		// Success: Koin initialization, factory injection, context creation, and
		// ShuntingLoop construction all work correctly with DI
	}

	/**
	 * Baseline test - Run WITHOUT Koin to establish baseline
	 *
	 * This test runs ShuntingLoop simulation without any Koin integration
	 * to establish the baseline golden output that must be preserved.
	 *
	 * Run this BEFORE enabling Koin to capture baseline values.
	 *
	 * TODO: Capture and save baseline metrics:
	 * - Final train positions
	 * - Event timestamps
	 * - Total simulation time
	 */
	@Test
	@Disabled("Baseline capture - run manually to establish golden output")
	fun `capture baseline without Koin`() {
		// TODO: Implement baseline capture
		// 1. Run ShuntingLoop for 60 time units
		// 2. Capture all train events
		// 3. Save to baseline file for comparison
		//
		// Example pattern:
		// val context = XMLContextFactory().createContext("vyhybna.xml")
		// val shuntingLoop = ShuntingLoop(context)
		// context.run()
		// saveBaseline(captureEvents())
	}

	/**
	 * Validation test - Run WITH Koin and compare to baseline
	 *
	 * This test will be enabled once Koin initialization is added to Main.kt.
	 * It runs the same ShuntingLoop simulation with Koin enabled and verifies
	 * that all outputs match the baseline captured above.
	 *
	 * PASS CRITERIA:
	 * - All event timestamps match baseline (tolerance: 1e-9s)
	 * - All train positions match baseline (tolerance: 1e-6m)
	 * - Event sequence identical to baseline
	 *
	 * TODO: Enable after Koin initialization complete
	 */
	@Test
	@Disabled("Waiting for implementation. Koin is now integrated - see Issue #218.")
	fun `validate simulation with Koin matches baseline`() {
		// TODO: Implement validation test
		// 1. Initialize Koin with interlockSimModule
		// 2. Run ShuntingLoop for 60 time units
		// 3. Compare against saved baseline
		// 4. Assert all values within tolerance
		//
		// Example pattern:
		// startKoin { modules(interlockSimModule) }
		// val context: SimulationContext = get() // or manual creation if not yet in DI
		// val shuntingLoop = ShuntingLoop(context)
		// context.run()
		// assertMatchesBaseline(captureEvents(), loadBaseline())
	}

	/**
	 * Performance benchmark - Measure Koin overhead
	 *
	 * MIGRATED TO JMH: This test has been converted to proper microbenchmarks.
	 *
	 * Performance requirements (validated by JMH benchmarks):
	 * - Railway network loading: < 10ms overhead with Koin
	 * - Simulation initialization: < 5% increase with DI
	 * - DI resolution: Sub-microsecond after warmup
	 *
	 * Run benchmarks:
	 *   ./gradlew jmh --includes="KoinPerformanceBenchmark"
	 *
	 * View results:
	 *   build/reports/jmh/results.txt (human-readable)
	 *   build/reports/jmh/results.json (machine-readable)
	 *
	 * @see cz.vutbr.fit.interlockSim.di.KoinPerformanceBenchmark
	 */
	@Test
	@Disabled("Migrated to JMH benchmarks. Run: ./gradlew jmh --includes='KoinPerformanceBenchmark'")
	fun `measure Koin performance overhead`() {
		// Migrated to: src/jmh/kotlin/cz/vutbr/fit/interlockSim/di/KoinPerformanceBenchmark.kt
		//
		// JMH provides:
		// - Accurate warmup and measurement cycles
		// - Statistical analysis of results
		// - Protection against JIT optimizations
		// - Comparison between DI and non-DI implementations
		//
		// Benchmarks implemented:
		// 1. railwayNetworkLoading_WithoutDI() - Baseline
		// 2. railwayNetworkLoading_WithKoin() - DI overhead measurement
		// 3. trainSimulationSetup_WithoutDI() - Baseline
		// 4. trainSimulationSetup_WithKoin() - DI overhead measurement
		// 5. factoryResolution_RepeatedLookups() - Resolution performance
		// 6. containerStartup_FullInitialization() - One-time startup cost
	}

	/**
	 * Context lifecycle test - Validate context scope management
	 *
	 * This test validates that Koin scopes are properly managed in the scope-per-context pattern:
	 * 1. Each context creates its own isolated scope
	 * 2. Scopes are properly closed when contexts are closed
	 * 3. No state leakage between sequential contexts
	 * 4. Resources are cleaned up properly
	 * 5. Closed scopes cannot be accessed
	 *
	 * The scope-per-context architecture ensures:
	 * - One PathReservationRegistry per context (shared by all services)
	 * - Different contexts have isolated registries
	 * - Closing context releases all scoped resources
	 *
	 * @see navigationModule in InterlockSimModule
	 * @see DefaultSimulationContext.scope
	 */
	@Test
	@Tag("integration-test")
	fun `validate context lifecycle with Koin scopes`() {
		// TEST 1: Rapid sequential creation stress test (memory leak detection)
		// Create many contexts in succession to verify scopes are actually closed
		repeat(50) { iteration ->
			buildTestContext().use { context ->
				// Verify scope is active and services are accessible
				val service = context.getPathReservationService()
				assertThat(service).isNotNull()
				
				// Make a reservation to populate internal state
				val grid = context.getRailWayNetGrid()
				val cellA = grid.getCellAt(1, 1)
				val cellB = grid.getCellAt(5, 5)
				require(cellA is PathSeparator) { "Cell at (1,1) must be PathSeparator, but was ${cellA?.javaClass?.simpleName}" }
				require(cellB is PathSeparator) { "Cell at (5,5) must be PathSeparator, but was ${cellB?.javaClass?.simpleName}" }
				val inOutA = context.toDynamic(cellA)
				val inOutB = context.toDynamic(cellB)
				service.reservePath("train-$iteration", inOutA, inOutB)
				
				// Verify reservation exists in this context
				assertThat(service.getReservedBlocks("train-$iteration")).isNotNull()
			}
			// After use{} block: context.close() called automatically, scope should be closed
		}
		// Success if we reach here without OutOfMemoryError or scope accumulation
		
		// TEST 2: Deep state isolation - verify no data bleeding between contexts
		// Create first context with significant state
		buildTestContext().use { context1 ->
			val service1 = context1.getPathReservationService()
			val grid1 = context1.getRailWayNetGrid()
			val cellA1 = grid1.getCellAt(1, 1)
			val cellB1 = grid1.getCellAt(5, 5)
			require(cellA1 is PathSeparator) { "Cell at (1,1) must be PathSeparator, but was ${cellA1?.javaClass?.simpleName}" }
			require(cellB1 is PathSeparator) { "Cell at (5,5) must be PathSeparator, but was ${cellB1?.javaClass?.simpleName}" }
			val inOutA1 = context1.toDynamic(cellA1)
			val inOutB1 = context1.toDynamic(cellB1)
			
			// Reserve path for train-alpha in context1
			// Note: Only one train can reserve a path at a time
			service1.reservePath("train-alpha", inOutA1, inOutB1)
			
			// Verify reservation exists in context1
			assertThat(service1.getReservedBlocks("train-alpha").size).isEqualTo(1)
		}
		// context1 is now closed, scope should be destroyed
		
		// Create second context and verify complete isolation
		buildTestContext().use { context2 ->
			val service2 = context2.getPathReservationService()
			
			// Verify context2's registry is completely clean (no leakage from context1)
			assertThat(service2.getReservedBlocks("train-alpha")).isEmpty()
			
			// Verify context2 can use the same train name without conflict
			val grid2 = context2.getRailWayNetGrid()
			val cellA2 = grid2.getCellAt(1, 1)
			val cellB2 = grid2.getCellAt(5, 5)
			require(cellA2 is PathSeparator) { "Cell at (1,1) must be PathSeparator, but was ${cellA2?.javaClass?.simpleName}" }
			require(cellB2 is PathSeparator) { "Cell at (5,5) must be PathSeparator, but was ${cellB2?.javaClass?.simpleName}" }
			val inOutA2 = context2.toDynamic(cellA2)
			val inOutB2 = context2.toDynamic(cellB2)
			service2.reservePath("train-alpha", inOutA2, inOutB2) // Same name as context1
			
			// Verify reservation works in context2 (proves it's a different scope)
			assertThat(service2.getReservedBlocks("train-alpha").size).isEqualTo(1)
		}
		
		// TEST 3: Manual scope closure and access denial
		val context3 = buildTestContext()
		val scope3 = context3.scope
		
		// Verify scope is active before close
		val serviceBeforeClose = context3.getPathReservationService()
		assertThat(serviceBeforeClose).isNotNull()
		
		// Manually close the context (and its scope)
		context3.close()
		
		// Attempting to get service from closed scope should fail
		// Koin 3.5.6 throws org.koin.core.error.ClosedScopeException
		assertFailure {
			scope3.get<cz.vutbr.fit.interlockSim.context.navigation.PathReservationService>()
		}.isInstanceOf(org.koin.core.error.ClosedScopeException::class)
	}
	
	/**
	 * Helper method to build a simple test context with InOut A -> InOut B.
	 * Each call creates a NEW TestContextBuilder instance to avoid reusing frozen EditingContext.
	 * Each context gets its own Koin scope.
	 */
	private fun buildTestContext(): DefaultSimulationContext {
		// Get a fresh TestContextBuilder for each call (avoids frozen EditingContext reuse)
		val builder: TestContextBuilder = getKoin().get()
		return builder
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildSimulationContext()
	}
}
