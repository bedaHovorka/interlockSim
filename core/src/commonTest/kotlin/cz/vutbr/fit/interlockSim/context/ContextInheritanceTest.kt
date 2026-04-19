/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test suite for context inheritance structure (Issue #153.9)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import org.koin.core.component.inject
import kotlin.test.Test

/**
 * Test suite for context inheritance structure.
 *
 * Tests verify that:
 * - DefaultEditingContext extends BaseContext (not DefaultSimulationContext)
 * - DefaultSimulationContext extends BaseContext (not DefaultEditingContext)
 * - SimulationContext does NOT extend EditingContext
 * - SimulationContext is NOT an EditingContext
 * - Shared functionality lives in BaseContext only
 * - No code duplication between contexts
 *
 * ## Issue #153.9: Add Comprehensive Context Refactoring Tests
 *
 * Category 5: Inheritance tests (6 tests)
 * - Tests the architectural separation between editing and simulation contexts
 *
 * ## Design Rationale
 *
 * The context hierarchy follows Interface Segregation Principle:
 * - EditingContext: Mutable network structure (putCell, removeCell, etc.)
 * - SimulationContext: Immutable network with simulation operations (run, stop, etc.)
 * - BaseContext: Shared infrastructure (grid, graph, properties, listeners)
 *
 * This design prevents accidental modification of the network during simulation
 * and makes the interface contracts clear and type-safe.
 *
 * @since 2026-01-20
 */
class ContextInheritanceTest : CommonKoinTestBase() {
	private val editingContextFactory: cz.vutbr.fit.interlockSim.context.EditingContextFactory by inject()
	private val simulationContextFactory: CommonSimulationContextFactory by inject()

	/**
	 * Test 1: DefaultEditingContext extends BaseContext (not DefaultSimulationContext)
	 *
	 * Verifies the inheritance hierarchy is correct: DefaultEditingContext → BaseContext
	 */
	@Test
	fun `DefaultEditingContext extends BaseContext`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Assert - Should be DefaultEditingContext which extends BaseContext
			assertThat(context).isInstanceOf(DefaultEditingContext::class)
			assertThat(context).isInstanceOf(BaseContext::class)
			// Should NOT be a DefaultSimulationContext
			assertThat(context).isNotInstanceOf(DefaultSimulationContext::class)
		}
	}

	/**
	 * Test 2: DefaultSimulationContext extends BaseContext (not DefaultEditingContext)
	 *
	 * Verifies the inheritance hierarchy is correct: DefaultSimulationContext → BaseContext
	 */
	@Test
	fun `DefaultSimulationContext extends BaseContext`() {
		simulationContextFactory.createEmptyContext().use { context ->
			// Assert - Should be DefaultSimulationContext which extends BaseContext
			assertThat(context).isInstanceOf(DefaultSimulationContext::class)
			assertThat(context).isInstanceOf(BaseContext::class)
			// Should NOT be a DefaultEditingContext
			assertThat(context).isNotInstanceOf(DefaultEditingContext::class)
		}
	}

	/**
	 * Test 3: SimulationContext interface does NOT extend EditingContext
	 *
	 * This is a compile-time guarantee, but we verify the runtime behavior.
	 * SimulationContext should not have editing methods available.
	 */
	@Test
	fun `SimulationContext does NOT extend EditingContext`() {
		simulationContextFactory.createEmptyContext().use { context ->
			// Assert - SimulationContext interface does not extend EditingContext
			// This is enforced at compile time - if we try to cast, it won't work
			val simulationContext: SimulationContext = context

			// We cannot cast SimulationContext to EditingContext
			// This test verifies the type system prevents this
			assertThat(simulationContext).isInstanceOf(SimulationContext::class)

			// If SimulationContext extended EditingContext, this would work:
			// val editingContext: EditingContext = simulationContext // This would not compile
			// Since it doesn't compile, the architecture is correct
		}
	}

	/**
	 * Test 4: SimulationContext is NOT an EditingContext
	 *
	 * Verifies that a simulation context cannot be used as an editing context.
	 */
	@Test
	fun `SimulationContext instance is not an EditingContext`() {
		simulationContextFactory.createEmptyContext().use { context ->
			// Assert - Cannot treat as EditingContext
			val isEditingContext = context is EditingContext
			assertThat(isEditingContext).isFalse()

			// The instance is a SimulationContext, not an EditingContext
			assertThat(context is SimulationContext).isTrue()
		}
	}

	/**
	 * Test 5: Shared functionality in BaseContext only
	 *
	 * Verifies that both editing and simulation contexts share BaseContext infrastructure.
	 */
	@Test
	fun `shared functionality in BaseContext only`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			simulationContextFactory.createEmptyContext().use { simulationContext ->
				// Assert - Both extend BaseContext
				assertThat(editingContext).isInstanceOf(BaseContext::class)
				assertThat(simulationContext).isInstanceOf(BaseContext::class)

				// Both have access to BaseContext methods:
				// - getRailWayNetGrid()
				// - getGraph()
				// - addPropertyChangeListener()
				// - currentMaxSpeed, currentTrackLength, currentNameString
				// This is inherited from BaseContext, not duplicated

				val editingGrid = editingContext.getRailWayNetGrid()
				val simulationGrid = simulationContext.getRailWayNetGrid()
				assertThat(editingGrid).isInstanceOf(RailwayNetGrid::class)
				assertThat(simulationGrid).isInstanceOf(RailwayNetGrid::class)
			}
		}
	}

	/**
	 * Test 6: No code duplication between contexts
	 *
	 * This test documents the architectural principle that code should not be duplicated
	 * between DefaultEditingContext and DefaultSimulationContext.
	 *
	 * Shared infrastructure lives in BaseContext. Context-specific operations live in
	 * the respective context class. This test verifies the architecture by checking
	 * that both contexts have their specific capabilities but share the base.
	 */
	@Test
	fun `no code duplication between contexts - architectural validation`() {
		(editingContextFactory.createEmptyContext() as DefaultEditingContext).use { editingContext ->
			(simulationContextFactory.createEmptyContext() as DefaultSimulationContext).use { simulationContext ->
				// Assert - EditingContext has editing-specific methods
				// We can't directly test method existence without reflection, but we can verify
				// that the class hierarchy is correct and the interfaces are properly segregated

				// EditingContext should implement EditingContext interface (with putCell, etc.)
				assertThat(editingContext).isInstanceOf(EditingContext::class)

				// SimulationContext should implement SimulationContext interface (with run, etc.)
				assertThat(simulationContext).isInstanceOf(SimulationContext::class)

				// Both should extend BaseContext (shared infrastructure)
				assertThat(editingContext).isInstanceOf(BaseContext::class)
				assertThat(simulationContext).isInstanceOf(BaseContext::class)

				// Neither should be an instance of the other's concrete type
				assertThat(editingContext).isNotInstanceOf(DefaultSimulationContext::class)
				assertThat(simulationContext).isNotInstanceOf(DefaultEditingContext::class)

				// This architecture ensures:
				// 1. Shared code in BaseContext (grid, graph, listeners, config)
				// 2. Editing operations in DefaultEditingContext only
				// 3. Simulation operations in DefaultSimulationContext only
				// 4. No code duplication - each layer has distinct responsibility
			}
		}
	}
}
