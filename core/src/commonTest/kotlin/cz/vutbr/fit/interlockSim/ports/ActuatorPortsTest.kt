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
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import kotlin.test.Test
import kotlin.test.assertFailsWith

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
		var lastDwellDuration: Double = -1.0

		override fun setTargetSpeed(speed: Double) {
			lastTargetSpeed = speed
		}

		override fun holdAtStation(dwellDurationSeconds: Double) {
			lastDwellDuration = dwellDurationSeconds
		}
	}

	@Test
	fun `TrainActuatorPort setTargetSpeed records the speed`() {
		val actuator = RecordingTrainActuator()
		actuator.setTargetSpeed(27.78) // 100 km/h in m/s
		assertThat(actuator.lastTargetSpeed).isEqualTo(27.78)
	}

	@Test
	fun `TrainActuatorPort setTargetSpeed zero means stop`() {
		val actuator = RecordingTrainActuator()
		actuator.setTargetSpeed(0.0)
		assertThat(actuator.lastTargetSpeed).isEqualTo(0.0)
	}

	@Test
	fun `TrainActuatorPort holdAtStation records the dwell duration`() {
		val actuator = RecordingTrainActuator()
		actuator.holdAtStation(60.0)
		assertThat(actuator.lastDwellDuration).isEqualTo(60.0)
	}

	@Test
	fun `TrainActuatorPort holdAtStation zero duration throws`() {
		val actuator = ContractValidatingTrainActuator()
		assertFailsWith<IllegalArgumentException> { actuator.holdAtStation(0.0) }
	}

	@Test
	fun `TrainActuatorPort holdAtStation negative duration throws`() {
		val actuator = ContractValidatingTrainActuator()
		assertFailsWith<IllegalArgumentException> { actuator.holdAtStation(-5.0) }
	}

	/**
	 * A minimal implementation that enforces the [TrainActuatorPort] input contract.
	 *
	 * Used to pin the `@throws IllegalArgumentException` precondition on negative speed —
	 * the interface itself cannot enforce it, so this stub documents the expected
	 * validation in executable form that real implementations should mirror.
	 */
	private class ContractValidatingTrainActuator : TrainActuatorPort {
		override fun setTargetSpeed(speed: Double) {
			require(speed >= 0.0) { "Target speed must be >= 0, got $speed" }
		}

		override fun holdAtStation(dwellDurationSeconds: Double) {
			require(dwellDurationSeconds > 0.0) {
				"dwellDurationSeconds must be > 0, got $dwellDurationSeconds"
			}
		}
	}

	@Test
	fun `TrainActuatorPort setTargetSpeed negative throws IllegalArgumentException`() {
		val actuator = ContractValidatingTrainActuator()
		assertFailsWith<IllegalArgumentException> { actuator.setTargetSpeed(-1.0) }
	}

	// ── NetworkActuatorPort ────────────────────────────────────────────────────

	/**
	 * A configurable stub implementation for [NetworkActuatorPort].
	 *
	 * Routes, route release, switch commands, and signal commands each have a
	 * pre-configured return value so tests can focus on one method at a time.  This stub
	 * does **not** enforce any input contracts — it records and returns whatever it is
	 * given.  See [ContractValidatingNetworkActuator] for contract enforcement.
	 */
	private class StubNetworkActuator(
		private val routeResult: RouteRequestResult = RouteRequestResult.Reserved("T1", 3),
		private val releaseResult: Boolean = true,
		private val switchResult: Boolean = true,
		private val signalResult: Boolean = true
	) : NetworkActuatorPort {
		var lastRouteTrainName: String? = null
		var lastRouteFrom: String? = null
		var lastRouteTo: String? = null
		var lastReleaseTrainName: String? = null
		var lastSwitchName: String? = null
		var lastSwitchPosition: RailSwitch.Conf? = null
		var lastSemaphoreName: String? = null
		var lastSignal: Signal? = null

		override fun requestRoute(
			trainName: String,
			fromEndpointName: String,
			toEndpointName: String
		): RouteRequestResult {
			lastRouteTrainName = trainName
			lastRouteFrom = fromEndpointName
			lastRouteTo = toEndpointName
			return routeResult
		}

		override fun releaseRoute(trainName: String): Boolean {
			lastReleaseTrainName = trainName
			return releaseResult
		}

		override fun setSwitchPosition(
			switchName: String,
			position: RailSwitch.Conf
		): Boolean {
			lastSwitchName = switchName
			lastSwitchPosition = position
			return switchResult
		}

		override fun setSignalAspect(
			semaphoreName: String,
			signal: Signal,
			trainName: String?
		): Boolean {
			lastSemaphoreName = semaphoreName
			lastSignal = signal
			// trainName is intentionally ignored -- this stub does not attribute writes (G5).
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

	@Test
	fun `NetworkActuatorPort setSignalAspect attributed overload records name and signal`() {
		val actuator = StubNetworkActuator()
		actuator.setSignalAspect("SEM_A", Signal.FREE, "T1")
		assertThat(actuator.lastSemaphoreName).isEqualTo("SEM_A")
		assertThat(actuator.lastSignal).isEqualTo(Signal.FREE)
		// trainName is intentionally ignored by this stub (no attribution, G5) -- there is no
		// lastSignalTrainName field, so the attributed overload must not record one.
	}

	@Test
	fun `NetworkActuatorPort setSignalAspect attributed overload returns impl result`() {
		val okActuator = StubNetworkActuator(signalResult = true)
		val failActuator = StubNetworkActuator(signalResult = false)
		assertThat(okActuator.setSignalAspect("SEM_A", Signal.STOP, "T1")).isTrue()
		assertThat(failActuator.setSignalAspect("SEM_A", Signal.STOP, "T1")).isFalse()
	}

	@Test
	fun `NetworkActuatorPort setSignalAspect 2-arg delegates to attributed overload`() {
		// The interface 2-arg default must delegate to the 3-arg (with null).  StubNetworkActuator
		// overrides only the 3-arg, so a 2-arg call exercises the interface default body and lands
		// in the 3-arg recording override.
		val actuator = StubNetworkActuator(signalResult = true)
		assertThat(actuator.setSignalAspect("SEM_A", Signal.FREE)).isTrue()
		assertThat(actuator.lastSemaphoreName).isEqualTo("SEM_A")
		assertThat(actuator.lastSignal).isEqualTo(Signal.FREE)
	}

	@Test
	fun `NetworkActuatorPort releaseRoute forwards the train name`() {
		val actuator = StubNetworkActuator()
		actuator.releaseRoute("T9")
		assertThat(actuator.lastReleaseTrainName).isEqualTo("T9")
	}

	@Test
	fun `NetworkActuatorPort releaseRoute returns impl result`() {
		val okActuator = StubNetworkActuator(releaseResult = true)
		val emptyActuator = StubNetworkActuator(releaseResult = false)
		assertThat(okActuator.releaseRoute("T9")).isTrue()
		assertThat(emptyActuator.releaseRoute("T9")).isFalse()
	}

	/**
	 * An implementation that enforces the [NetworkActuatorPort.requestRoute] input
	 * contract: blank [trainName] and unknown endpoint names throw fast rather than being
	 * surfaced as a [RouteRequestResult].  Real implementations should mirror this.
	 *
	 * @param validEndpointNames the set of endpoint names the network recognises.
	 * @param routeResult the result to return once preconditions pass.
	 */
	private class ContractValidatingNetworkActuator(
		private val validEndpointNames: Set<String>,
		private val routeResult: RouteRequestResult = RouteRequestResult.Reserved("T1", 1)
	) : NetworkActuatorPort {
		override fun requestRoute(
			trainName: String,
			fromEndpointName: String,
			toEndpointName: String
		): RouteRequestResult {
			require(trainName.isNotBlank()) { "trainName must be non-blank" }
			require(fromEndpointName in validEndpointNames) { "Unknown endpoint: $fromEndpointName" }
			require(toEndpointName in validEndpointNames) { "Unknown endpoint: $toEndpointName" }
			return routeResult
		}

		override fun releaseRoute(trainName: String): Boolean {
			require(trainName.isNotBlank()) { "trainName must be non-blank" }
			return true
		}

		override fun setSwitchPosition(
			switchName: String,
			position: RailSwitch.Conf
		): Boolean = true

		override fun setSignalAspect(
			semaphoreName: String,
			signal: Signal
		): Boolean = true

		// Attributed form: this contract fake does not attribute writes (G5), so it delegates
		// to the 2-arg form above.  The 2-arg override is kept to break the delegation cycle the
		// interface default would otherwise create (2-arg default -> 3-arg -> 2-arg default ...).
		override fun setSignalAspect(
			semaphoreName: String,
			signal: Signal,
			trainName: String?
		): Boolean = setSignalAspect(semaphoreName, signal)
	}

	@Test
	fun `NetworkActuatorPort requestRoute blank trainName throws`() {
		val actuator = ContractValidatingNetworkActuator(validEndpointNames = setOf("IN", "OUT"))
		assertFailsWith<IllegalArgumentException> { actuator.requestRoute("", "IN", "OUT") }
	}

	@Test
	fun `NetworkActuatorPort requestRoute unknown fromEndpointName throws`() {
		val actuator = ContractValidatingNetworkActuator(validEndpointNames = setOf("IN", "OUT"))
		assertFailsWith<IllegalArgumentException> { actuator.requestRoute("T1", "NOPE", "OUT") }
	}

	@Test
	fun `NetworkActuatorPort requestRoute unknown toEndpointName throws`() {
		val actuator = ContractValidatingNetworkActuator(validEndpointNames = setOf("IN", "OUT"))
		assertFailsWith<IllegalArgumentException> { actuator.requestRoute("T1", "IN", "NOPE") }
	}

	@Test
	fun `NetworkActuatorPort releaseRoute blank trainName throws`() {
		val actuator = ContractValidatingNetworkActuator(validEndpointNames = setOf("IN", "OUT"))
		assertFailsWith<IllegalArgumentException> { actuator.releaseRoute("") }
	}

	@Test
	fun `NetworkActuatorPort setSignalAspect attributed overload delegates on contract fake`() {
		// ContractValidatingNetworkActuator's 3-arg delegates to its 2-arg (which returns true).
		val actuator = ContractValidatingNetworkActuator(validEndpointNames = setOf("IN", "OUT"))
		assertThat(actuator.setSignalAspect("SEM_A", Signal.FREE, "T1")).isTrue()
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
		assertThat(result.fromEndpointName).isEqualTo("IN_A")
		assertThat(result.toEndpointName).isEqualTo("OUT_B")
	}

	@Test
	fun `RouteRequestResult AllPathsBlocked carries attemptedPaths count`() {
		val result = RouteRequestResult.AllPathsBlocked(7)
		assertThat(result.attemptedPaths).isEqualTo(7)
	}

	@Test
	fun `RouteRequestResult Conflict carries blockName and existingOwner`() {
		val result = RouteRequestResult.Conflict("blockX", "T2")
		assertThat(result.blockName).isEqualTo("blockX")
		assertThat(result.existingOwner).isEqualTo("T2")
	}

	@Test
	fun `RouteRequestResult Conflict blockName may be null for unnamed block`() {
		val result = RouteRequestResult.Conflict(null, "T3")
		assertThat(result.blockName).isNull()
		assertThat(result.existingOwner).isEqualTo("T3")
	}

	@Test
	fun `RouteRequestResult when expression is exhaustive`() {
		// Compiling the `when` without an `else` branch verifies the sealed hierarchy is
		// exhaustive: a new subtype added later is a compile error here.  The assertions
		// also pin the actual mapping so the test is not a no-op.
		fun describe(result: RouteRequestResult): String =
			when (result) {
				is RouteRequestResult.Reserved -> "reserved"
				is RouteRequestResult.NoRouteExists -> "no-route"
				is RouteRequestResult.AllPathsBlocked -> "blocked"
				is RouteRequestResult.Conflict -> "conflict"
				is RouteRequestResult.OriginNotContiguous -> "origin-not-contiguous"
				is RouteRequestResult.ConditionFailed -> "condition-failed"
				is RouteRequestResult.GeometricallyImpossible -> "geometrically-impossible"
			}
		assertThat(describe(RouteRequestResult.Reserved("T1", 3))).isEqualTo("reserved")
		assertThat(describe(RouteRequestResult.NoRouteExists("A", "B"))).isEqualTo("no-route")
		assertThat(describe(RouteRequestResult.AllPathsBlocked(2))).isEqualTo("blocked")
		assertThat(describe(RouteRequestResult.Conflict("B", "T2"))).isEqualTo("conflict")
		assertThat(describe(RouteRequestResult.OriginNotContiguous("doA1", "T1 is not at doA1")))
			.isEqualTo("origin-not-contiguous")
		assertThat(describe(RouteRequestResult.ConditionFailed("reason", retryable = true)))
			.isEqualTo("condition-failed")
		assertThat(describe(RouteRequestResult.GeometricallyImpossible("reason")))
			.isEqualTo("geometrically-impossible")
	}

	@Test
	fun `requestRoute returns Reserved when stub is configured so`() {
		val expected = RouteRequestResult.Reserved("T2", 2)
		val actuator = StubNetworkActuator(routeResult = expected)
		val result = actuator.requestRoute("T2", "IN", "OUT")
		assertThat(result)
			.isInstanceOf<RouteRequestResult.Reserved>()
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
		val actuator =
			StubNetworkActuator(
				routeResult = RouteRequestResult.NoRouteExists("X", "Y")
			)
		val result = actuator.requestRoute("T4", "X", "Y")
		assertThat(result).isInstanceOf(RouteRequestResult.NoRouteExists::class)
	}
}
