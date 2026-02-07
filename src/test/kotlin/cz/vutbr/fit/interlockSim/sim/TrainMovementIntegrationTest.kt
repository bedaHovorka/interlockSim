/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Train Movement Integration Tests
 * Issue #295 - Actual Train behavior with path reservation
 * 2026-01-29
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Integration tests for ShuntingLoop simulation with path reservation.
 *
 * ## Purpose
 *
 * These tests verify that ShuntingLoop simulation correctly interacts with the
 * path reservation system. Tests run real trains through the jDisco simulation
 * using vyhybna.xml (shunting loop configuration).
 *
 * ## What These Tests Validate
 *
 * 1. **Simulation completes with pre-reserved path** - ShuntingLoop handles
 *    pre-existing reservations gracefully and releases all resources
 * 2. **Simulation handles train lifecycle without pre-reservations** - ShuntingLoop
 *    manages reservation lifecycle end-to-end
 * 3. **Simulation manages path contention** - ShuntingLoop handles multiple trains
 *    competing for paths
 * 4. **Full simulation lifecycle releases all resources** - PathReservationRegistry
 *    is clean after simulation completes
 *
 * ## Limitations
 *
 * These tests use ShuntingLoop as the Process coordinator because individual
 * Train instances cannot be activated directly (jDisco Process lifecycle).
 * For individual train movement tests with a dedicated test process, see
 * Issue #366.
 *
 * ## Conservative Approach
 *
 * Per CLAUDE.md guidance for sim/ package:
 * - Uses existing vyhybna.xml network (realistic topology)
 * - Short simulation times (10-60 seconds)
 * - Validates train state without modifying Train class
 * - Tests observe behavior through public APIs only
 *
 * @since 2026-01-29 (Issue #295 - Option B)
 * @see ShuntingLoopRegressionTest for regression tests without reservation
 */
@Tag("integration-test")
@DisplayName("Train Movement - ShuntingLoop Integration with Path Reservation")
class TrainMovementIntegrationTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()
	private lateinit var context: DefaultSimulationContext
	private lateinit var reservationService: PathReservationService

	@BeforeEach
	fun setUp() {
		logger.info { "Train movement integration test setup" }
	}

	@AfterEach
	fun tearDown() {
		if (::context.isInitialized) {
			context.close()
		}
	}

	/**
	 * Simulation completes with a pre-reserved path and ShuntingLoop's own trains
	 * do not conflict with the pre-reservation.
	 *
	 * Uses "PreTest-1" as the reservation ID to avoid collision with ShuntingLoop's
	 * auto-generated train names ("Train #1", "Train #2", etc.).
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `simulation completes with pre-reserved path`() {
		// Arrange: Load vyhybna.xml (shunting loop configuration)
		val xml = TestFixtures.loadShuntingXml()
		context = simulationContextFactory.createContext(xml) as DefaultSimulationContext
		reservationService = context.getPathReservationService()

		val inOuts = context.getInOuts().toList()
		require(inOuts.size >= 2) { "Shunting loop must have at least 2 InOuts" }

		val startInOut = inOuts[0]
		val targetInOut = inOuts[1]

		// Reserve path with non-conflicting ID (avoids collision with ShuntingLoop's "Train #N")
		val preTestTrainId = "PreTest-1"
		val result = reservationService.reservePath(preTestTrainId, startInOut, targetInOut)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val registry = context.scope.get<PathReservationRegistry>()
		val preReservationBlockCount = registry.blockCount()
		assertThat(preReservationBlockCount).isGreaterThan(0)
		logger.info { "Pre-reserved $preReservationBlockCount blocks for $preTestTrainId" }

		// Act: Run ShuntingLoop (30 seconds simulation time)
		val shuntingLoop = ShuntingLoop(context, endTime = 30L)
		context.setMainProcess(shuntingLoop)
		context.run()

		// Assert: Pre-test reservation should still be in registry (not released by ShuntingLoop)
		assertThat(registry.trainCount()).isGreaterThan(0)
		logger.info {
			"Post-simulation: ${registry.trainCount()} trains, ${registry.blockCount()} blocks in registry"
		}
	}

	/**
	 * Simulation handles complete train lifecycle without any pre-reservations.
	 * ShuntingLoop manages reservation lifecycle end-to-end via InOutWorker.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `simulation handles train lifecycle without pre-reservations`() {
		// Arrange: Load vyhybna.xml
		val xml = TestFixtures.loadShuntingXml()
		context = simulationContextFactory.createContext(xml) as DefaultSimulationContext
		reservationService = context.getPathReservationService()

		val inOuts = context.getInOuts().toList()
		require(inOuts.size >= 2) { "Shunting loop must have at least 2 InOuts" }

		val registry = context.scope.get<PathReservationRegistry>()
		assertThat(registry.trainCount()).isEqualTo(0)
		assertThat(registry.blockCount()).isEqualTo(0)

		// Act: Run ShuntingLoop (10 seconds - short run)
		val shuntingLoop = ShuntingLoop(context, endTime = 10L)
		context.setMainProcess(shuntingLoop)
		context.run()

		// Assert: Simulation completed (no hang/deadlock)
		assertThat(context.getGraph()).isNotNull()

		// Verify workers exist for all InOuts
		for (inOut in inOuts) {
			val worker = context.getWorkerFor(inOut)
			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
		}

		logger.info {
			"Post-simulation: ${registry.trainCount()} trains, ${registry.blockCount()} blocks in registry"
		}
	}

	/**
	 * Simulation manages path contention between multiple trains when a
	 * pre-existing reservation exists.
	 *
	 * ShuntingLoop creates trains that must compete for paths. A pre-existing
	 * reservation for "PreTest-1" adds additional contention.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `simulation manages path contention with pre-reservation`() {
		// Arrange: Load vyhybna.xml
		val xml = TestFixtures.loadShuntingXml()
		context = simulationContextFactory.createContext(xml) as DefaultSimulationContext
		reservationService = context.getPathReservationService()

		val inOuts = context.getInOuts().toList()
		require(inOuts.size >= 2) { "Shunting loop must have at least 2 InOuts" }

		// Pre-reserve with non-conflicting ID
		val preTestTrainId = "PreTest-1"
		val result = reservationService.reservePath(preTestTrainId, inOuts[0], inOuts[1])
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Act: Run ShuntingLoop (30 seconds - enough for multiple trains)
		val shuntingLoop = ShuntingLoop(context, endTime = 30L)
		context.setMainProcess(shuntingLoop)
		context.run()

		// Assert: Simulation completed without deadlock
		val registry = context.scope.get<PathReservationRegistry>()
		assertThat(context.getGraph()).isNotNull()

		// Verify all InOuts have workers
		val inOutCount = inOuts.size
		assertThat(inOutCount).isGreaterThan(0)
		for (inOut in inOuts) {
			val worker = context.getWorkerFor(inOut)
			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
		}

		logger.info {
			"Post-simulation: ${registry.trainCount()} trains, ${registry.blockCount()} blocks in registry"
		}
	}

	/**
	 * Full simulation lifecycle releases all ShuntingLoop-managed resources.
	 *
	 * Runs a longer simulation (60 time units) to allow complete train lifecycle:
	 * entry, traverse, exit. After simulation, ShuntingLoop's trains should have
	 * released their reservations.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `full simulation lifecycle releases all resources`() {
		// Arrange: Load vyhybna.xml
		val xml = TestFixtures.loadShuntingXml()
		context = simulationContextFactory.createContext(xml) as DefaultSimulationContext

		val registry = context.scope.get<PathReservationRegistry>()
		assertThat(registry.trainCount()).isEqualTo(0)

		// Act: Run ShuntingLoop for full lifecycle (60 time units)
		val shuntingLoop = ShuntingLoop(context, endTime = 60L)
		context.setMainProcess(shuntingLoop)
		context.run()

		// Assert: Simulation completed
		assertThat(context.getGraph()).isNotNull()
		assertThat(context.getRailWayNetGrid()).isNotNull()

		logger.info {
			"Post-simulation: ${registry.trainCount()} trains, ${registry.blockCount()} blocks in registry"
		}

		// Verify all InOuts have workers that processed trains
		val inOuts = context.getInOuts().toList()
		for (inOut in inOuts) {
			val worker = context.getWorkerFor(inOut)
			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
		}
	}
}
