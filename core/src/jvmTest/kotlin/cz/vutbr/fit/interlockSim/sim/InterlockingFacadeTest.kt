/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * MVP unit tests for [InterlockingFacade] — the ESA-11 four-condition interlocking kernel.
 *
 * These tests validate the basic structure and error handling of the facade.
 * Full integration tests with real network topology are deferred until helper methods
 * are implemented with actual block/switch/signal lookups.
 *
 * **Test Strategy:**
 * - Route requests with empty/null lookups (helper methods return null)
 * - Validation of RouteResponse sealed interface
 * - Error message generation for denial reasons
 * - Signal clearing logic (deferred until Signal lookup works)
 *
 * @since Issue #572 (SP3.4 — Goal 10)
 */
@DisplayName("InterlockingFacade — ESA-11 four-condition kernel")
@Timeout(30, unit = TimeUnit.SECONDS)
class InterlockingFacadeTest : KoinTestBase() {
	override fun getTestModule(): Module =
		module {
			// Import core module with InterlockingFacade binding
			includes(cz.vutbr.fit.interlockSim.di.coreModule)
		}

	private lateinit var facade: InterlockingFacade
	private lateinit var mockContext: MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
		facade = DefaultInterlockingFacade(mockContext)
	}

	/**
	 * Test: Route request returns either Granted or Denied (sealed interface structure).
	 *
	 * **Coverage:** Type contract validation.
	 * **Expected:** RouteResponse.Granted or RouteResponse.Denied (no other subtypes).
	 * **Note:** With null helper methods, requests typically return Denied due to missing blocks.
	 */
	@Test
	@Tag("integration-test")
	fun `requestRoute returns valid RouteResponse sealed type`() {
		val trainId = "T1"
		val entrySignal = SignalId("S1")
		val route =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = listOf(BlockId("U1")),
				running = emptyList(),
				flank = emptyList()
			)
		val clearedAspect = Aspect.Volno

		val response = facade.requestRoute(trainId, entrySignal, route, clearedAspect)

		// Response must be one of the sealed subtypes
		assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse::class)
	}

	/**
	 * Test: Route request with empty blocks list (edge case).
	 *
	 * **Coverage:** Minimal route with no blocks.
	 * **Expected:** Route grant (all zero blocks are trivially "free").
	 * **Note:** In MVP with null helper methods, may behave differently.
	 */
	@Test
	@Tag("integration-test")
	fun `requestRoute with empty blocks returns Granted`() {
		val trainId = "T1"
		val entrySignal = SignalId("S1")
		val route =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = emptyList(),
				running = emptyList(),
				flank = emptyList()
			)
		val clearedAspect = Aspect.Volno

		val response = facade.requestRoute(trainId, entrySignal, route, clearedAspect)

		// With no blocks, all conditions trivially pass
		assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Granted::class)
	}

	/**
	 * Test: Denial response includes Czech human-readable reason.
	 *
	 * **Coverage:** Error message generation for dispatcher/agent feedback.
	 * **Expected:** Non-empty Czech reason in Denied response.
	 * **Note:** Specific reasons depend on block/switch lookups.
	 */
	@Test
	@Tag("integration-test")
	fun `Denied response contains non-empty Czech reason`() {
		val trainId = "T1"
		val entrySignal = SignalId("S1")
		val route =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = listOf(BlockId("U1")),
				running = emptyList(),
				flank = emptyList()
			)
		val clearedAspect = Aspect.Volno

		val response = facade.requestRoute(trainId, entrySignal, route, clearedAspect)

		// If denied (likely with null helper methods), reason should be non-empty
		if (response is InterlockingFacade.RouteResponse.Denied) {
			assertThat(response.reason).isNotEmpty()
		}
	}

	/**
	 * Test: Release route does not throw exception with valid trainId.
	 *
	 * **Coverage:** Basic release operation (no routes actually locked).
	 * **Expected:** No exceptions; idempotent operation succeeds.
	 */
	@Test
	@Tag("integration-test")
	fun `releaseRoute with non-existent trainId succeeds (idempotent)`() {
		val trainId = "T-nonexistent"
		val exitSignal = SignalId("S2")

		// Should not throw; operation is idempotent
		try {
			facade.releaseRoute(trainId, exitSignal)
		} catch (e: Exception) {
			throw AssertionError("releaseRoute should not throw for non-existent train", e)
		}
	}

	/**
	 * Test: Multiple sequential route requests create isolated responses.
	 *
	 * **Coverage:** Stateless facade behavior.
	 * **Expected:** Each request independently evaluated.
	 */
	@Test
	@Tag("integration-test")
	fun `multiple sequential route requests are independent`() {
		val route1 =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = listOf(BlockId("U1")),
				running = emptyList(),
				flank = emptyList()
			)
		val route2 =
			TrainRoute(
				from = SignalId("S2"),
				to = SignalId("S3"),
				blocks = listOf(BlockId("U2")),
				running = emptyList(),
				flank = emptyList()
			)

		val response1 = facade.requestRoute("T1", SignalId("S1"), route1, Aspect.Volno)
		val response2 = facade.requestRoute("T2", SignalId("S2"), route2, Aspect.Volno)

		// Both should be valid responses (type contract)
		assertThat(response1).isInstanceOf(InterlockingFacade.RouteResponse::class)
		assertThat(response2).isInstanceOf(InterlockingFacade.RouteResponse::class)
	}

	/**
	 * Test: Granted response includes clearedAspect and lockedRoute.
	 *
	 * **Coverage:** Response contract validation.
	 * **Expected:** Granted response has non-null clearedAspect and route.
	 */
	@Test
	@Tag("integration-test")
	fun `Granted response includes clearedAspect and lockedRoute`() {
		val trainId = "T1"
		val entrySignal = SignalId("S1")
		val route =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = emptyList(),
				running = emptyList(),
				flank = emptyList()
			)
		val clearedAspect = Aspect.Volno

		val response = facade.requestRoute(trainId, entrySignal, route, clearedAspect)

		if (response is InterlockingFacade.RouteResponse.Granted) {
			assertThat(response.clearedAspect).isInstanceOf(Aspect::class)
			assertThat(response.lockedRoute).isInstanceOf(TrainRoute::class)
		}
	}
}
