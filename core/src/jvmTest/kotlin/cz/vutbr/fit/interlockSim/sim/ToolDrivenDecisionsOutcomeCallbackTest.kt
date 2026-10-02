/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Simulation Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRelease
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * Issue #960: [applyToolDrivenToActuator] reports what the actuator answered through its
 * `onOutcome` callback, so the asynchronous applier can build its observation outcome from the
 * shared helper instead of inlining a copy of it.
 */
@DisplayName("applyToolDrivenToActuator reports its outcome through onOutcome (Issue #960)")
class ToolDrivenDecisionsOutcomeCallbackTest {
	private fun applyCollecting(
		decision: DispatchDecision,
		actuator: NetworkActuatorPort
	): List<ToolDrivenOutcome> {
		val outcomes = mutableListOf<ToolDrivenOutcome>()
		decision.applyToolDrivenToActuator(actuator, "unit-test") { outcomes += it }
		return outcomes
	}

	@ParameterizedTest(name = "applied={0}")
	@ValueSource(booleans = [true, false])
	fun `SetSignalAspect reports SignalSet exactly once`(applied: Boolean) {
		val actuator =
			mockk<NetworkActuatorPort>().also {
				every { it.setSignalAspect("zA", Signal.FREE, "T-1") } returns applied
			}

		val outcomes = applyCollecting(DispatchDecision.SetSignalAspect("zA", Signal.FREE, "T-1"), actuator)

		assertThat(outcomes).isEqualTo(listOf<ToolDrivenOutcome>(ToolDrivenOutcome.SignalSet(applied)))
		verify(exactly = 1) { actuator.setSignalAspect("zA", Signal.FREE, "T-1") }
	}

	@ParameterizedTest(name = "applied={0}")
	@ValueSource(booleans = [true, false])
	fun `SetSwitchPosition reports SwitchSet exactly once`(applied: Boolean) {
		val actuator =
			mockk<NetworkActuatorPort>().also {
				every { it.setSwitchPosition("v1", RailSwitch.Conf.BRANCH) } returns applied
			}

		val outcomes = applyCollecting(DispatchDecision.SetSwitchPosition("v1", RailSwitch.Conf.BRANCH), actuator)

		assertThat(outcomes).isEqualTo(listOf<ToolDrivenOutcome>(ToolDrivenOutcome.SwitchSet(applied)))
		verify(exactly = 1) { actuator.setSwitchPosition("v1", RailSwitch.Conf.BRANCH) }
	}

	@Test
	fun `ReleaseRoute reports the RouteRelease the actuator returned exactly once`() {
		val release = RouteRelease(anyReleased = true, deferredBlockIds = listOf("k1"))
		val actuator =
			mockk<NetworkActuatorPort>().also { every { it.releaseRouteDetailed("T-1") } returns release }

		val outcomes = applyCollecting(DispatchDecision.ReleaseRoute("T-1"), actuator)

		assertThat(outcomes).isEqualTo(listOf<ToolDrivenOutcome>(ToolDrivenOutcome.RouteReleased(release)))
		verify(exactly = 1) { actuator.releaseRouteDetailed("T-1") }
	}

	@Test
	fun `RequestRoute reports the RouteRequestResult the actuator returned exactly once`() {
		val result = RouteRequestResult.Reserved("T-1", 2)
		val actuator =
			mockk<NetworkActuatorPort>().also { every { it.requestRoute("T-1", "zA", "doB1") } returns result }

		val outcomes = applyCollecting(DispatchDecision.RequestRoute("T-1", "zA", "doB1"), actuator)

		assertThat(outcomes).isEqualTo(listOf<ToolDrivenOutcome>(ToolDrivenOutcome.RouteRequested(result)))
		verify(exactly = 1) { actuator.requestRoute("T-1", "zA", "doB1") }
	}

	@Test
	fun `the default no-op callback still applies the decision`() {
		val actuator =
			mockk<NetworkActuatorPort>().also { every { it.setSwitchPosition("v1", RailSwitch.Conf.MAIN) } returns true }

		DispatchDecision.SetSwitchPosition("v1", RailSwitch.Conf.MAIN).applyToolDrivenToActuator(actuator, "unit-test")

		verify(exactly = 1) { actuator.setSwitchPosition("v1", RailSwitch.Conf.MAIN) }
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("nonToolDecisions")
	fun `a non-tool decision is a programming error and reports nothing`(decision: DispatchDecision) {
		val outcomes = mutableListOf<ToolDrivenOutcome>()

		assertFailure {
			decision.applyToolDrivenToActuator(mockk(), "unit-test") { outcomes += it }
		}.isInstanceOf(IllegalStateException::class)
		assertThat(outcomes).isEmpty()
	}

	companion object {
		@JvmStatic
		fun nonToolDecisions(): Stream<Arguments> =
			Stream.of(
				Arguments.of(DispatchDecision.NoAction),
				Arguments.of(DispatchDecision.ApproveTrain("T-1")),
				Arguments.of(DispatchDecision.ReservePath("T-1", "zA", "doB1")),
				Arguments.of(DispatchDecision.HoldTrain("T-1", 5.0))
			)
	}
}
