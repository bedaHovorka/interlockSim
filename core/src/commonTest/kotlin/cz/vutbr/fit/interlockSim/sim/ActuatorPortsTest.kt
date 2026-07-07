/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for TrainActuatorPort and NetworkActuatorPort interfaces (SP0.3, Issue #542).
 * Verifies interface contracts, result-type exhaustiveness, and minimal-impl correctness.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.isFalse
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import kotlin.test.Test

/**
 * Unit tests for the SP0.3 actuator port interfaces.
 *
 * These tests verify:
 * - [TrainActuatorPort] can be implemented and called correctly.
 * - [NetworkActuatorPort] can be implemented and called correctly.
 * - [RouteRequestResult] sealed subtypes carry the expected properties.
 * - Exhaustive `when` patterns compile on [RouteRequestResult].
 *
 * @since Issue #542 (SP0.3 — Goal 10)
 */
class ActuatorPortsTest {

	// ── TrainActuatorPort ──────────────────────────────────────────────────────

	/**
	 * A minimal stub implementation that records what it was told to do.
	 */
	private class RecordingTrainActuator : TrainActuatorPort {
		var lastTargetSpeed: Double = -1.0

		override fun setTargetSpeed(speed: Double) {
			lastTargetSpeed = speed
		}
	}

	@Test
	fun `TrainActuatorPort setTargetSpeed records the speed`() {
		val actuator = RecordingTrainActuator()
		actuator.setTargetSpeed(27.78)  // 100 km/h in m/s
		assertThat(actuator.lastTargetSpeed).isEqualTo(27.78)
	}

	@Test
	fun `TrainActuatorPort setTargetSpeed zero means stop`() {
		val actuator = RecordingTrainActuator()
		actuator.setTargetSpeed(0.0)
		assertThat(actuator.lastTargetSpeed).isEqualTo(0.0)
	}

	// ── NetworkActuatorPort ────────────────────────────────────────────────────

	/**
	 * A configurable stub implementation for [NetworkActuatorPort].
	 *
	 * Routes, switch commands, and signal commands each have a pre-configured return
	 * value so tests can focus on one method at a time.
	 */
	private class StubNetworkActuator(
		private val routeResult: RouteRequestResult = RouteRequestResult.Reserved("T1", 3),
		private val switchResult: Boolean = true,
		private val signalResult: Boolean = true
	) : NetworkActuatorPort {
		var lastRouteTrainName: String? = null
		var lastRouteFrom: String? = null
		var lastRouteTo: String? = null
		var lastSwitchName: String? = null
		var lastSwitchPosition: RailSwitch.Conf? = null
		var lastSemaphoreName: String? = null
		var lastSignal: Signal? = null

		override fun requestRoute(
			trainName: String,
			fromInOutName: String,
			toInOutName: String
		): RouteRequestResult {
			lastRouteTrainName = trainName
			lastRouteFrom = fromInOutName
			lastRouteTo = toInOutName
			return routeResult
		}

		override fun setSwitchPosition(switchName: String, position: RailSwitch.Conf): Boolean {
			lastSwitchName = switchName
			lastSwitchPosition = position
			return switchResult
		}

		override fun setSignalAspect(semaphoreName: String, signal: Signal): Boolean {
			lastSemaphoreName = semaphoreName
			lastSignal = signal
			return signalResult
		}
	}

	@Test
	fun `NetworkActuatorPort requestRoute forwards all parameters`() {
		val actuator = StubNetworkActuator()
		actuator.requestRoute("T1", "IN_NORTH", "OUT_SOUTH")
		assertThat(actuator.lastRouteTrainName).isEqualTo("T1")
		assertThat(actuator.lastRouteFrom).isEqualTo("IN_NORTH")
		assertThat(actuator.lastRouteTo).isEqualTo("OUT_SOUTH")
	}

	@Test
	fun `NetworkActuatorPort setSwitchPosition forwards name and conf`() {
		val actuator = StubNetworkActuator()
		actuator.setSwitchPosition("SW1", RailSwitch.Conf.BRANCH)
		assertThat(actuator.lastSwitchName).isEqualTo("SW1")
		assertThat(actuator.lastSwitchPosition).isEqualTo(RailSwitch.Conf.BRANCH)
	}

	@Test
	fun `NetworkActuatorPort setSwitchPosition returns impl result`() {
		val okActuator = StubNetworkActuator(switchResult = true)
		val failActuator = StubNetworkActuator(switchResult = false)
		assertThat(okActuator.setSwitchPosition("SW1", RailSwitch.Conf.MAIN)).isTrue()
		assertThat(failActuator.setSwitchPosition("SW1", RailSwitch.Conf.MAIN)).isFalse()
	}

	@Test
	fun `NetworkActuatorPort setSignalAspect forwards name and signal`() {
		val actuator = StubNetworkActuator()
		actuator.setSignalAspect("SEM_A", Signal.FREE)
		assertThat(actuator.lastSemaphoreName).isEqualTo("SEM_A")
		assertThat(actuator.lastSignal).isEqualTo(Signal.FREE)
	}

	@Test
	fun `NetworkActuatorPort setSignalAspect returns impl result`() {
		val okActuator = StubNetworkActuator(signalResult = true)
		val failActuator = StubNetworkActuator(signalResult = false)
		assertThat(okActuator.setSignalAspect("SEM_A", Signal.STOP)).isTrue()
		assertThat(failActuator.setSignalAspect("SEM_A", Signal.STOP)).isFalse()
	}

	// ── RouteRequestResult ─────────────────────────────────────────────────────

	@Test
	fun `RouteRequestResult Reserved carries trainName and blocksCount`() {
		val result = RouteRequestResult.Reserved("T5", 4)
		assertThat(result.trainName).isEqualTo("T5")
		assertThat(result.blocksCount).isEqualTo(4)
	}

	@Test
	fun `RouteRequestResult NoRouteExists carries endpoint names`() {
		val result = RouteRequestResult.NoRouteExists("IN_A", "OUT_B")
		assertThat(result.fromInOutName).isEqualTo("IN_A")
		assertThat(result.toInOutName).isEqualTo("OUT_B")
	}

	@Test
	fun `RouteRequestResult AllPathsBlocked carries attemptedPaths count`() {
		val result = RouteRequestResult.AllPathsBlocked(7)
		assertThat(result.attemptedPaths).isEqualTo(7)
	}

	@Test
	fun `RouteRequestResult when expression is exhaustive`() {
		// This test verifies the sealed hierarchy is exhaustive by compiling the when
		// expression without an else branch.  A compile error here means a subtype was
		// added without updating all callers.
		val results: List<RouteRequestResult> = listOf(
			RouteRequestResult.Reserved("T1", 3),
			RouteRequestResult.NoRouteExists("A", "B"),
			RouteRequestResult.AllPathsBlocked(2)
		)
		for (result in results) {
			val description: String = when (result) {
				is RouteRequestResult.Reserved        -> "reserved"
				is RouteRequestResult.NoRouteExists   -> "no-route"
				is RouteRequestResult.AllPathsBlocked -> "blocked"
			}
			assertThat(description).isInstanceOf(String::class)
		}
	}

	@Test
	fun `requestRoute returns Reserved when stub is configured so`() {
		val expected = RouteRequestResult.Reserved("T2", 2)
		val actuator = StubNetworkActuator(routeResult = expected)
		val result = actuator.requestRoute("T2", "IN", "OUT")
		assertThat(result).isInstanceOf(RouteRequestResult.Reserved::class)
		assertThat(result as RouteRequestResult.Reserved)
			.prop(RouteRequestResult.Reserved::trainName)
			.isEqualTo("T2")
	}

	@Test
	fun `requestRoute returns AllPathsBlocked when stub is configured so`() {
		val actuator = StubNetworkActuator(routeResult = RouteRequestResult.AllPathsBlocked(1))
		val result = actuator.requestRoute("T3", "IN", "OUT")
		assertThat(result).isInstanceOf(RouteRequestResult.AllPathsBlocked::class)
	}

	@Test
	fun `requestRoute returns NoRouteExists when stub is configured so`() {
		val actuator = StubNetworkActuator(
			routeResult = RouteRequestResult.NoRouteExists("X", "Y")
		)
		val result = actuator.requestRoute("T4", "X", "Y")
		assertThat(result).isInstanceOf(RouteRequestResult.NoRouteExists::class)
	}
}
