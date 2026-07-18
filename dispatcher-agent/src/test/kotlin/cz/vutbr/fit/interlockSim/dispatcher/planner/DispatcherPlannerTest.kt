/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import assertk.fail
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlannerCapabilities], [DispatcherPlanner], and [RuleBasedPlanAdapter].
 *
 * @since Issue #574 (SP3.6 — Goal 10)
 */
@DisplayName("SP3.6 Pluggable planner interface")
class DispatcherPlannerTest {
	// ── PlannerCapabilities ──────────────────────────────────────────────────

	@Nested
	@DisplayName("PlannerCapabilities")
	inner class PlannerCapabilitiesTests {
		@Test
		@DisplayName("UNRESTRICTED is Double.MAX_VALUE")
		fun unrestrictedIsDoubleMaxValue() {
			assertThat(PlannerCapabilities.UNRESTRICTED).isEqualTo(Double.MAX_VALUE)
		}

		@Test
		@DisplayName("AGENT_MAX_SPEED_MULTIPLIER is 2.0 (Issue #187 / #574 owner decision)")
		fun agentMaxSpeedMultiplierIsTwo() {
			assertThat(PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER).isEqualTo(2.0)
		}

		@Test
		@DisplayName("AGENT_MAX_SPEED_MULTIPLIER is less than UNRESTRICTED")
		fun agentMultiplierLessThanUnrestricted() {
			assertThat(PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER)
				.isLessThan(PlannerCapabilities.UNRESTRICTED)
		}

		@Test
		@DisplayName("maxSpeedMultiplier must be positive — zero throws IllegalArgumentException")
		fun zeroMaxSpeedMultiplierThrows() {
			var caught: IllegalArgumentException? = null
			try {
				PlannerCapabilities(name = "bad", isAsynchronous = false, maxSpeedMultiplier = 0.0)
			} catch (e: IllegalArgumentException) {
				caught = e
			}
			assertThat(caught != null).isTrue()
		}

		@Test
		@DisplayName("maxSpeedMultiplier must be positive — negative throws IllegalArgumentException")
		fun negativeMaxSpeedMultiplierThrows() {
			var caught: IllegalArgumentException? = null
			try {
				PlannerCapabilities(name = "bad", isAsynchronous = false, maxSpeedMultiplier = -1.0)
			} catch (e: IllegalArgumentException) {
				caught = e
			}
			assertThat(caught != null).isTrue()
		}

		@Test
		@DisplayName("valid capabilities can be constructed")
		fun validCapabilitiesConstruct() {
			val cap =
				PlannerCapabilities(
					name = "TestPlanner",
					isAsynchronous = true,
					maxSpeedMultiplier = PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER
				)
			assertThat(cap.name).isEqualTo("TestPlanner")
			assertThat(cap.isAsynchronous).isTrue()
			assertThat(cap.maxSpeedMultiplier).isEqualTo(PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER)
		}
	}

	// ── RuleBasedPlanAdapter capabilities ────────────────────────────────────

	@Nested
	@DisplayName("RuleBasedPlanAdapter capabilities")
	inner class RuleBasedPlanAdapterCapabilities {
		private val adapter = RuleBasedPlanAdapter(RuleBasedDispatcher())

		@Test
		@DisplayName("capabilities.name is 'RuleBased'")
		fun capabilitiesNameIsRuleBased() {
			assertThat(adapter.capabilities.name).isEqualTo("RuleBased")
		}

		@Test
		@DisplayName("capabilities.isAsynchronous is false")
		fun capabilitiesIsNotAsynchronous() {
			assertThat(adapter.capabilities.isAsynchronous).isFalse()
		}

		@Test
		@DisplayName("capabilities.maxSpeedMultiplier is UNRESTRICTED")
		fun capabilitiesMaxSpeedIsUnrestricted() {
			assertThat(adapter.capabilities.maxSpeedMultiplier)
				.isEqualTo(PlannerCapabilities.UNRESTRICTED)
		}
	}

	// ── RuleBasedPlanAdapter.plan() delegation ────────────────────────────────

	@Nested
	@DisplayName("RuleBasedPlanAdapter.plan() delegates to Dispatcher.decide()")
	inner class RuleBasedPlanAdapterDelegation {
		private val adapter = RuleBasedPlanAdapter(RuleBasedDispatcher())

		private fun emptyObservation(): DispatchObservation =
			DispatchObservation(
				snapshot =
					SimulationSnapshot(
						simTime = 0.0,
						semaphores = emptyList(),
						blocks = emptyList(),
						trainPositions = emptyList(),
						timetables = emptyList()
					),
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)

		@Test
		@DisplayName("plan() with empty observation returns at least NoAction")
		fun planWithEmptyObservationReturnsNoAction() {
			val decisions = runBlocking { adapter.plan(emptyObservation()) }
			assertThat(decisions.size).isGreaterThan(0)
			assertThat(decisions.contains(DispatchDecision.NoAction)).isTrue()
		}

		@Test
		@DisplayName("plan() returns without suspending (synchronous adapter)")
		fun planReturnsSynchronously() {
			// plan() should be callable inside runBlocking without extra delay
			var completed = false
			runBlocking {
				adapter.plan(emptyObservation())
				completed = true
			}
			assertThat(completed).isTrue()
		}
	}
}

// AssertK extension helpers not available in core assertk for Double comparisons
private fun assertk.Assert<Double>.isLessThan(expected: Double) {
	given { actual ->
		if (actual >= expected) {
			fail("Expected <$actual> to be less than <$expected>")
		}
	}
}
