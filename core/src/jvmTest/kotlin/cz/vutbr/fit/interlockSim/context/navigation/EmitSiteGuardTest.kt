/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import cz.vutbr.fit.interlockSim.sim.onRunEnded
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * The delivery guard of `DefaultSimulationContext` at the emit sites a plain `ShuntingLoop` or
 * `MultiTrainLoop` run does not reach (Issue #1361; `CustomEventDeliveryGuardRunTest` covers the others):
 * the registry-conflict rollback, the switch-claim release, the double occupancy and the run-end contention
 * flush. A raw `onSimulationEvent` subscriber registered first throws on the payload; the scenario's outcome
 * is the one without it, and a raw and a typed subscriber registered after it still get the event.
 *
 * @since Issue #1361
 */
@DisplayName("Delivery guard at the emit sites of the block and reservation events (Issue #1361)")
// A separate thread, so a regression that keeps the run spinning fails instead of hanging.
@Timeout(30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EmitSiteGuardTest : EmitSiteScenarios() {
	/** What a scenario run in a context left behind. */
	private data class ContextOutcome(
		val result: Any?,
		val failure: String?,
		val rawSubscriberSaw: List<String>,
		val typedSubscribersSaw: List<String>,
		val runEnded: Boolean
	)

	/** What the observers registered by [registerObservers] observe during a scenario run. */
	private class Observations {
		var thrown = 0
		val raw = mutableListOf<String>()
		val typed = mutableListOf<String>()
		var runEnded = false
	}

	private fun kindOf(payload: Any?): String = payload?.let { it::class.simpleName } ?: "null"

	/**
	 * Runs [scenario] once in the main process of the test's context, with a first raw subscriber that
	 * throws on [throwOn] when it is given; the second value is how often it threw. A typed block listener
	 * may be made to throw with [typedThrowOn]. A raw and a typed subscriber registered after the throwing
	 * one record every payload they get. The work is split into [registerObservers] and
	 * [runScenarioInMainProcess].
	 */
	private fun inContext(
		throwOn: KClass<*>?,
		typedThrowOn: KClass<*>? = null,
		scenario: () -> Any?
	): Pair<ContextOutcome, Int> {
		val observations = registerObservers(throwOn, typedThrowOn)
		val (result, failure) = runScenarioInMainProcess(scenario)
		val outcome = ContextOutcome(result, failure, observations.raw, observations.typed, observations.runEnded)
		return outcome to observations.thrown
	}

	/**
	 * Registers the scenario run's observers and returns what they observe. The throwing subscribers come
	 * first: [throwOn] makes a first raw subscriber throw on the payload, [typedThrowOn] makes a typed
	 * block listener throw. Registered after them, a later raw subscriber and the typed listeners record
	 * every payload they get, and the run-ended flag observes the end of the run.
	 */
	private fun registerObservers(
		throwOn: KClass<*>?,
		typedThrowOn: KClass<*>?
	): Observations {
		val observations = Observations()
		if (throwOn != null) {
			simulationContext.onSimulationEvent { event ->
				if (throwOn.isInstance(payloadOf(event))) {
					observations.thrown++
					error("raw subscriber failure injected by the test")
				}
			}
		}
		if (typedThrowOn != null) {
			simulationContext.onBlockEvent { event ->
				if (typedThrowOn.isInstance(event)) {
					observations.thrown++
					error("typed block listener failure injected by the test")
				}
			}
		}
		simulationContext.onSimulationEvent { event ->
			if (event is SimulationEvent.Custom) observations.raw += kindOf(event.payload)
		}
		simulationContext.onBlockEvent { observations.typed += kindOf(it) }
		simulationContext.onConflictDetectedEvent { observations.typed += kindOf(it) }
		simulationContext.onSwitchClaimsReleased { observations.typed += kindOf(it) }
		simulationContext.getCollisionServices().onCollisionWarning { observations.typed += kindOf(it) }
		simulationContext.onRunEnded { observations.runEnded = true }
		return observations
	}

	/**
	 * Runs [scenario] once in the main process of the test's context and returns its result and failure
	 * message. An `Exception` the scenario throws is the failure; a thrown assertion failure is not one:
	 * the run still ends, and the assertion is rethrown afterwards to fail the test.
	 */
	private fun runScenarioInMainProcess(scenario: () -> Any?): Pair<Any?, String?> {
		var result: Any? = null
		var failure: String? = null
		var escaped: Throwable? = null
		simulationContext.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					try {
						result = scenario()
					} catch (e: Exception) {
						failure = "${e::class.simpleName}: ${e.message}"
					} catch (e: Throwable) {
						// An assertion inside the scenario: still end the run, then fail the test with it.
						escaped = e
					}
					terminate()
				}
			}
		)
		simulationContext.run()
		// The run ended with it, so the assertion fails the test now.
		escaped?.let { throw it }
		return result to failure
	}

	@ParameterizedTest(name = "a first raw subscriber throwing on {0}")
	@ValueSource(strings = ["ReservationConflictDetected", "ConflictDetectedEvent"])
	fun `a raw subscriber throwing on a rollback conflict event does not change the reservation`(payload: String) {
		val throwOn = conflictPayload(payload)
		val (outcome, thrown) = inContext(throwOn, scenario = registryConflictScenario())

		assertThat(thrown, "the raw subscriber threw").isGreaterThan(0)
		assertThat(outcome.failure, "the reserving process").isNull()
		assertThat(outcome.result, "the reservation")
			.isNotNull()
			.isInstanceOf(PathReservationService.ReservationResult.Conflict::class)
		val conflictKinds = setOf("ReservationConflictDetected", "ConflictDetectedEvent")
		assertThat(outcome.rawSubscriberSaw.filter { it in conflictKinds }, "a later raw subscriber").containsExactly(
			"ReservationConflictDetected",
			"ConflictDetectedEvent"
		)
		assertThat(outcome.typedSubscribersSaw.filter { it in conflictKinds }, "the typed subscribers").containsExactly(
			"ReservationConflictDetected",
			"ConflictDetectedEvent"
		)
		assertThat(outcome.runEnded, "the run ended with its run-end delivery").isTrue()
	}

	@Test
	fun `a raw subscriber throwing on SwitchClaimsReleased leaves the teardown and the later subscribers as without it`() {
		val (outcome, thrown) = inContext(SwitchClaimsReleased::class) { unregisterScenario() }

		assertThat(thrown, "the raw subscriber threw").isEqualTo(1)
		assertThat(outcome.failure, "the tearing-down process").isNull()
		assertThat(outcome.rawSubscriberSaw.filter { it == "SwitchClaimsReleased" }, "a later raw subscriber")
			.containsExactly("SwitchClaimsReleased")
		assertThat(outcome.typedSubscribersSaw.filter { it == "SwitchClaimsReleased" }, "onSwitchClaimsReleased")
			.containsExactly("SwitchClaimsReleased")
		val (reserved, released) = outcome.result as Pair<*, *>
		assertThat(released, "blocks released = blocks reserved").isEqualTo(reserved)
	}

	@Test
	fun `a raw subscriber throwing on BlockEntryViolation leaves the double occupancy as without it`() {
		val (outcome, thrown) = inContext(CollisionWarning.BlockEntryViolation::class) { doubleOccupancyScenario() }

		assertThat(thrown, "the raw subscriber threw").isEqualTo(1)
		// The second entry fails on the occupancy check, as without the subscriber -- not on the injected failure.
		assertThat(outcome.result, "the second entry's failure").isEqualTo(OCCUPANCY_CHECK_FAILURE)
		assertThat(outcome.rawSubscriberSaw.filter { it == "BlockEntryViolation" }, "a later raw subscriber")
			.containsExactly("BlockEntryViolation")
		assertThat(outcome.typedSubscribersSaw.filter { it == "BlockEntryViolation" }, "the collision warning listener")
			.containsExactly("BlockEntryViolation")
	}

	@Test
	fun `a typed block listener throwing on the run-end contention flush still ends the run with its run-end delivery`() {
		val (outcome, thrown) =
			inContext(throwOn = null, typedThrowOn = BlockEvent.ReservationConflictDetected::class) {
				contentionScenario()
			}

		assertThat(
			outcome.result,
			"the second request"
		).isNotNull().isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)
		assertThat(thrown, "the typed listener threw on the flushed event").isEqualTo(1)
		assertThat(outcome.typedSubscribersSaw.filter { it == "ReservationConflictDetected" }, "a later typed listener")
			.containsExactly("ReservationConflictDetected")
		assertThat(outcome.runEnded, "the run-end delivery").isTrue()
	}
}
