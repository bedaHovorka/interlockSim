/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.ConflictHintLatch
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultAutoConflictResolutionService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Architecture test converting the Goal 9 C7 ruling into a CI-enforced invariant
 * (SP2c.4, Issue #827).
 *
 * ## C7 ruling summary (from Issue #822 investigation)
 *
 * `AutoConflictResolutionService` and `ConflictResolver` are advisory, pure-read components:
 * - The public API is one advisory method: `applyTopRanked(event): ConflictResolution?`
 * - It mutates nothing simulation-related — no reservation, no cancellation, no signal change.
 * - KDoc says the result is *"purely advisory … the caller is responsible for enacting the
 *   simulation effect"*.
 * - It has zero production call sites in the dispatcher agent loop (only test call sites).
 * - It is NOT reachable in `shuntingLoopAI`: `wireDispatcherAgent` never resolves
 *   `ConflictResolver` or `AutoConflictResolutionService`.
 *
 * **Option (a) adopted:** demote to information source — `ConflictDetectedEvent` feeds
 * affordance reasons via [ConflictHintLatch]. The LLM remains the only actor.
 *
 * ## What this test enforces
 *
 * 1. **Dispatcher-agent non-conflict classes** (`DispatchDecisionApplier`, `AgentLoopDriver`,
 *    `ActuatorCommandQueue`) hold **no field** of type `ConflictResolver` or
 *    `AutoConflictResolutionService` — these are reachable from `:dispatcher-agent` only
 *    through the annotator path ([AffordanceAnnotator] → [ConflictHintLatch] →
 *    [ConflictDetectedEvent]).
 *
 * 2. **Goal 9 conflict classes** (`DefaultAutoConflictResolutionService`) hold **no field** of
 *    actuator types (`ActuatorCommandQueue`, `NetworkActuatorPort`, `DispatchLoopActuatorPort`)
 *    — prevents the conflict-resolution engine from ever enacting decisions.
 *
 * 3. **[ConflictHintLatch]** holds **no field** of any actuator type — it is a pure
 *    perception-path accumulator.
 *
 * Tests use reflection on `declaredFields`, mirroring the style in
 * [ActionValidatorPurityTest].
 *
 * @since Issue #827 (SP2c.4 — Goal 10, Goal 9 C7 ruling)
 */
@DisplayName("Goal 9 C7 ruling: architecture invariants enforced in CI (SP2c.4 #827)")
class Goal9C7ArchitectureTest {
	// ── Forbidden actuator type names (simple class name suffixes) ─────────────

	private val actuatorTypeNames =
		listOf("ActuatorCommandQueue", "NetworkActuatorPort", "DispatchLoopActuatorPort")

	/** Forbidden conflict-resolution types accessible from :dispatcher-agent only via the annotator path. */
	private val conflictOrchestratorNames =
		listOf("ConflictResolver", "AutoConflictResolutionService")

	// ── Helper ──────────────────────────────────────────────────────────────────

	private fun assertNoForbiddenFields(
		clazz: Class<*>,
		forbidden: List<String>
	) {
		val violating =
			clazz.declaredFields
				.map { it.type.name }
				.filter { fieldType -> forbidden.any { name -> fieldType.contains(name) } }
		assert(violating.isEmpty()) {
			"${clazz.simpleName} must not hold fields of type(s) $forbidden; found: $violating"
		}
	}

	// ── 1. DispatchDecisionApplier: no conflict orchestrator fields ──────────

	@Nested
	@DisplayName("DispatchDecisionApplier: no ConflictResolver or AutoConflictResolutionService fields")
	inner class DispatchDecisionApplierConflictIsolation {
		@Test
		@DisplayName("DispatchDecisionApplier holds no ConflictResolver field")
		fun dispatchDecisionApplierHoldsNoConflictResolverField() {
			assertNoForbiddenFields(DispatchDecisionApplier::class.java, listOf("ConflictResolver"))
		}

		@Test
		@DisplayName("DispatchDecisionApplier holds no AutoConflictResolutionService field")
		fun dispatchDecisionApplierHoldsNoAutoConflictResolutionServiceField() {
			assertNoForbiddenFields(DispatchDecisionApplier::class.java, listOf("AutoConflictResolutionService"))
		}
	}

	// ── 2. AgentLoopDriver: no conflict orchestrator fields ──────────────────

	@Nested
	@DisplayName("AgentLoopDriver: no ConflictResolver or AutoConflictResolutionService fields")
	inner class AgentLoopDriverConflictIsolation {
		@Test
		@DisplayName("AgentLoopDriver holds no ConflictResolver field")
		fun agentLoopDriverHoldsNoConflictResolverField() {
			assertNoForbiddenFields(AgentLoopDriver::class.java, listOf("ConflictResolver"))
		}

		@Test
		@DisplayName("AgentLoopDriver holds no AutoConflictResolutionService field")
		fun agentLoopDriverHoldsNoAutoConflictResolutionServiceField() {
			assertNoForbiddenFields(AgentLoopDriver::class.java, listOf("AutoConflictResolutionService"))
		}
	}

	// ── 3. ActuatorCommandQueue: no conflict orchestrator fields ─────────────

	@Nested
	@DisplayName("ActuatorCommandQueue: no ConflictResolver or AutoConflictResolutionService fields")
	inner class ActuatorCommandQueueConflictIsolation {
		@Test
		@DisplayName("ActuatorCommandQueue holds no ConflictResolver field")
		fun actuatorCommandQueueHoldsNoConflictResolverField() {
			assertNoForbiddenFields(ActuatorCommandQueue::class.java, listOf("ConflictResolver"))
		}

		@Test
		@DisplayName("ActuatorCommandQueue holds no AutoConflictResolutionService field")
		fun actuatorCommandQueueHoldsNoAutoConflictResolutionServiceField() {
			assertNoForbiddenFields(ActuatorCommandQueue::class.java, listOf("AutoConflictResolutionService"))
		}
	}

	// ── 4. DefaultAutoConflictResolutionService: no actuator fields ───────────

	@Nested
	@DisplayName("DefaultAutoConflictResolutionService: no ActuatorCommandQueue or NetworkActuatorPort fields")
	inner class DefaultAutoConflictResolutionServiceActuatorIsolation {
		@Test
		@DisplayName("DefaultAutoConflictResolutionService holds no ActuatorCommandQueue field")
		fun defaultAutoConflictResolutionServiceHoldsNoActuatorCommandQueueField() {
			assertNoForbiddenFields(DefaultAutoConflictResolutionService::class.java, listOf("ActuatorCommandQueue"))
		}

		@Test
		@DisplayName("DefaultAutoConflictResolutionService holds no NetworkActuatorPort field")
		fun defaultAutoConflictResolutionServiceHoldsNoNetworkActuatorPortField() {
			assertNoForbiddenFields(DefaultAutoConflictResolutionService::class.java, listOf("NetworkActuatorPort"))
		}

		@Test
		@DisplayName("DefaultAutoConflictResolutionService holds no DispatchLoopActuatorPort field")
		fun defaultAutoConflictResolutionServiceHoldsNoDispatchLoopActuatorPortField() {
			assertNoForbiddenFields(
				DefaultAutoConflictResolutionService::class.java,
				listOf("DispatchLoopActuatorPort")
			)
		}
	}

	// ── 5. ConflictHintLatch: no actuator fields (pure perception accumulator) ──

	@Nested
	@DisplayName("ConflictHintLatch: no actuator fields (perception-path only)")
	inner class ConflictHintLatchActuatorIsolation {
		@Test
		@DisplayName("ConflictHintLatch holds no actuator-type fields")
		fun conflictHintLatchHoldsNoActuatorFields() {
			assertNoForbiddenFields(ConflictHintLatch::class.java, actuatorTypeNames)
		}

		@Test
		@DisplayName("ConflictHintLatch holds no ConflictResolver field (not an orchestrator)")
		fun conflictHintLatchHoldsNoConflictResolverField() {
			assertNoForbiddenFields(ConflictHintLatch::class.java, listOf("ConflictResolver"))
		}

		@Test
		@DisplayName("ConflictHintLatch holds no AutoConflictResolutionService field (not an orchestrator)")
		fun conflictHintLatchHoldsNoAutoConflictResolutionServiceField() {
			assertNoForbiddenFields(ConflictHintLatch::class.java, listOf("AutoConflictResolutionService"))
		}
	}

	// ── 6. AffordanceAnnotator: no actuator fields ────────────────────────────

	@Nested
	@DisplayName("AffordanceAnnotator: no actuator fields (annotation is perception-only)")
	inner class AffordanceAnnotatorActuatorIsolation {
		@Test
		@DisplayName("AffordanceAnnotator holds no actuator-type fields")
		fun affordanceAnnotatorHoldsNoActuatorFields() {
			assertNoForbiddenFields(AffordanceAnnotator::class.java, actuatorTypeNames)
		}
	}
}
