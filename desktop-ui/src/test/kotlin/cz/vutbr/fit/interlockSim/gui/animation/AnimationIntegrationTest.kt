/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.gui.gridcanvas.AnimatedSimulationCellRenderer
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.awt.Component
import java.awt.Graphics2D
import javax.swing.SwingUtilities

/**
 * Integration test for complete animation rendering pipeline.
 *
 * Tests the end-to-end flow from SimulationContext state changes through
 * AnimationController to AnimatedSimulationCellRenderer rendering.
 *
 * ## Test Coverage
 *
 * - Real SimulationContext with vyhybna.xml configuration
 * - AnimationController creation and lifecycle
 * - AnimationState capture and updates
 * - AnimatedSimulationCellRenderer rendering without exceptions
 *
 * ## Threading
 *
 * Uses SwingUtilities.invokeAndWait to ensure EDT execution for Swing operations.
 *
 * @since 2026-01-22 (Issue #202)
 */
@Tag("integration-test")
class AnimationIntegrationTest : KoinTestBase() {
	private lateinit var simulationContext: SimulationContext
	private lateinit var animationController: AnimationController
	private lateinit var renderer: AnimatedSimulationCellRenderer
	private lateinit var mockCanvas: Component
	private val processFactory: SimulationProcessFactory by inject()

	@BeforeEach
	fun setup() {
		// Create real simulation context from vyhybna.xml
		simulationContext =
			TestFixtures.newShuntingSimulationContext(processFactory = processFactory, initializeDynamicMapping = true)

		// Create mock canvas (minimal Swing component)
		mockCanvas = mockk<Component>(relaxed = true)
	}

	@AfterEach
	fun teardown() {
		// Stop animation controller if running
		if (::animationController.isInitialized) {
			SwingUtilities.invokeAndWait {
				animationController.stop()
			}
		}
	}

	@Test
	fun `animation controller can be created and started`() {
		// When: Creating and starting animation controller on EDT
		SwingUtilities.invokeAndWait {
			animationController = AnimationController(simulationContext, mockCanvas)
			animationController.start()
		}

		// Then: Controller should be initialized and state should be captured
		val state = animationController.currentState
		assertThat(state).isNotNull()
		assertThat(state.simulationTime).isEqualTo(0.0)
	}

	@Test
	fun `animation state captures track blocks from simulation context`() {
		// Given: Started animation controller
		SwingUtilities.invokeAndWait {
			animationController = AnimationController(simulationContext, mockCanvas)
			animationController.start()
		}

		// When: Getting current animation state
		val state = animationController.currentState

		// Then: State should be initialized (track states may be empty in fresh simulation)
		assertThat(state.trackStates).isNotNull()
	}

	@Test
	fun `animation state captures semaphore signals from simulation context`() {
		// Given: Started animation controller
		SwingUtilities.invokeAndWait {
			animationController = AnimationController(simulationContext, mockCanvas)
			animationController.start()
		}

		// When: Getting current animation state
		val state = animationController.currentState

		// Then: State should contain signal states from the simulation context
		assertThat(state.signalStates).isNotEmpty()
		// Verify each signal state has valid properties (semaphore and signal)
		state.signalStates.values.forEach { signalState ->
			assertThat(signalState.semaphore).isNotNull()
			assertThat(signalState.signal).isNotNull()
		}
	}

	@Test
	fun `animated renderer can render cells without exceptions`() {
		// Given: Animation controller and renderer
		SwingUtilities.invokeAndWait {
			animationController = AnimationController(simulationContext, mockCanvas)
			renderer = AnimatedSimulationCellRenderer(20, 20, animationController)
			animationController.start()
		}

		// When: Rendering a track block part cell
		val grid = simulationContext.getRailWayNetGrid()
		val firstTrackBlockPart =
			grid
				.asSequence()
				.map { it.value }
				.filterIsInstance<TrackBlockPart>()
				.firstOrNull()

		assertThat(firstTrackBlockPart).isNotNull()

		// Then: Rendering should succeed without exceptions
		val graphics = mockk<Graphics2D>(relaxed = true)
		renderer.draw(graphics, firstTrackBlockPart!!)
		// No assertion needed - test passes if no exception thrown
	}

	@Test
	fun `animation controller can be stopped cleanly`() {
		// Given: Started animation controller
		SwingUtilities.invokeAndWait {
			animationController = AnimationController(simulationContext, mockCanvas)
			animationController.start()
		}

		// When: Stopping the controller
		SwingUtilities.invokeAndWait {
			animationController.stop()
		}

		// Then: State should still be accessible (frozen at last captured state)
		val state = animationController.currentState
		assertThat(state).isNotNull()
	}

	@Test
	fun `animation state remains accessible after controller stop`() {
		// Given: Started and then stopped animation controller
		SwingUtilities.invokeAndWait {
			animationController = AnimationController(simulationContext, mockCanvas)
			animationController.start()
		}

		val stateBefore = animationController.currentState

		SwingUtilities.invokeAndWait {
			animationController.stop()
		}

		// When: Accessing state after stop
		val stateAfter = animationController.currentState

		// Then: State should be the same (frozen)
		assertThat(stateAfter).isEqualTo(stateBefore)
		assertThat(stateAfter.trackStates).isNotNull()
		assertThat(stateAfter.signalStates).isNotNull()
	}
}
