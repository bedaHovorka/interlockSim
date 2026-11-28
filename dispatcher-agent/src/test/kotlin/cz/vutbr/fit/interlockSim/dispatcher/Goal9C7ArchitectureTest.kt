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
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultConflictResolver
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Architecture test converting the Goal 9 C7 ruling into a CI-enforced invariant
 * (SP2c.4, Issue #827).
 *
 * ## C7 ruling summary (from Issue #822 investigation)
 *
 * `ConflictResolver` is an advisory, pure-read component:
 * - It generates ranked candidates and mutates nothing simulation-related — no reservation,
 *   no cancellation, no signal change.
 * - It has zero production call sites in the dispatcher agent loop.
 * - `wireDispatcherAgent` never resolves `ConflictResolver` as a direct field of the
 *   dispatcher-agent classes below.
 *
 * **Option (a) adopted:** demote to information source — `ConflictDetectedEvent` feeds
 * affordance reasons via [ConflictHintLatch]. The LLM remains the only actor.
 *
 * ## Goal 9B revision (Issue #1453, SP2.1)
 *
 * The SP2c.4 ruling also froze the headless auto-resolution picker
 * (`AutoConflictResolutionService`, its `applyTopRanked` and its default implementation) as an
 * inert information source, pinned by this test. Owner ruling R1 (#1434) reverses that freeze:
 * Goal 9B takes conflict resolution to the dispatcher thread, so the picker was deleted. Its
 * checks were dropped here; that it stays deleted is pinned by `Goal9ARemovedSymbolsTripwireTest`
 * (`:core`). The `ConflictResolver` checks stay, and the actuator-isolation check that guarded the
 * picker now guards [DefaultConflictResolver], the conflict engine that remains.
 *
 * ## What this test enforces
 *
 * 1. **Dispatcher-agent non-conflict classes** (`DispatchDecisionApplier`, `AgentLoopDriver`,
 *    `ActuatorCommandQueue`) hold **no direct field** of type `ConflictResolver`
 *    (no direct field ownership). The reflection check below
 *    inspects `Class.declaredFields` field-type names only — reachability via DI/Koin scope,
 *    constructor parameters, or call chains is **not** asserted here.
 *
 * 2. **Goal 9 conflict classes** ([DefaultConflictResolver]) hold **no field** of
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
	@DisplayName("DispatchDecisionApplier: no ConflictResolver fields")
	inner class DispatchDecisionApplierConflictIsolation {
		@Test
		@DisplayName("DispatchDecisionApplier holds no ConflictResolver field")
		fun dispatchDecisionApplierHoldsNoConflictResolverField() {
			assertNoForbiddenFields(DispatchDecisionApplier::class.java, listOf("ConflictResolver"))
		}
	}

	// ── 2. AgentLoopDriver: no conflict orchestrator fields ──────────────────

	@Nested
	@DisplayName("AgentLoopDriver: no ConflictResolver fields")
	inner class AgentLoopDriverConflictIsolation {
		@Test
		@DisplayName("AgentLoopDriver holds no ConflictResolver field")
		fun agentLoopDriverHoldsNoConflictResolverField() {
			assertNoForbiddenFields(AgentLoopDriver::class.java, listOf("ConflictResolver"))
		}
	}

	// ── 3. ActuatorCommandQueue: no conflict orchestrator fields ─────────────

	@Nested
	@DisplayName("ActuatorCommandQueue: no ConflictResolver fields")
	inner class ActuatorCommandQueueConflictIsolation {
		@Test
		@DisplayName("ActuatorCommandQueue holds no ConflictResolver field")
		fun actuatorCommandQueueHoldsNoConflictResolverField() {
			assertNoForbiddenFields(ActuatorCommandQueue::class.java, listOf("ConflictResolver"))
		}
	}

	// ── 4. DefaultConflictResolver: no actuator fields ───────────────────────

	@Nested
	@DisplayName("DefaultConflictResolver: no ActuatorCommandQueue or NetworkActuatorPort fields")
	inner class DefaultConflictResolverActuatorIsolation {
		@Test
		@DisplayName("DefaultConflictResolver holds no ActuatorCommandQueue field")
		fun defaultConflictResolverHoldsNoActuatorCommandQueueField() {
			assertNoForbiddenFields(DefaultConflictResolver::class.java, listOf("ActuatorCommandQueue"))
		}

		@Test
		@DisplayName("DefaultConflictResolver holds no NetworkActuatorPort field")
		fun defaultConflictResolverHoldsNoNetworkActuatorPortField() {
			assertNoForbiddenFields(DefaultConflictResolver::class.java, listOf("NetworkActuatorPort"))
		}

		@Test
		@DisplayName("DefaultConflictResolver holds no DispatchLoopActuatorPort field")
		fun defaultConflictResolverHoldsNoDispatchLoopActuatorPortField() {
			assertNoForbiddenFields(
				DefaultConflictResolver::class.java,
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
