/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Simple Test Process Coordinator for Unit Testing
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Minimal test Process coordinator for isolated Train unit testing.
 *
 * This Process extends LoopProcess to coordinate a single Train instance
 * within the jDisco simulation framework. Unlike ShuntingLoop (which manages
 * multiple trains, queues, and dispatching), SimpleTestProcess focuses on
 * single-train scenarios for unit testing.
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * val train = Train(context, timetable)
 * val testProcess = SimpleTestProcess(context, train, endTime = 10.0)
 * context.setMainProcess(testProcess)
 * context.run()
 *
 * // Validate after simulation
 * assertThat(train.getVelocity()).isGreaterThan(0.0)
 * ```
 *
 * ## Conservative Approach
 *
 * Per CLAUDE.md guidance for sim/ package:
 * - Minimal changes to simulation code
 * - Thoroughly tested before use
 * - Follows existing LoopProcess patterns
 * - No modification of existing simulation classes
 *
 * ## jDisco Process Lifecycle
 *
 * The Process lifecycle follows this pattern (from LoopProcess base class):
 *
 * 1. **Construction** - Process created but not active
 * 2. **activate()** - Transitions Process to ACTIVE state, begins execution
 * 3. **startAction()** - Called once when Process starts
 * 4. **iteration()** - Called repeatedly until terminate() is called
 * 5. **interLoopSleep()** - Called between iterations to advance simulation time
 * 6. **byTerminateAction()** - Called once when Process terminates
 *
 * ## Implementation Notes
 *
 * - **Never override actions()** - LoopProcess implements this as infinite loop
 * - **Use activate(train)** - Transitions Train from constructed to running state
 * - **Use hold(duration)** - Advances simulation time (blocks until time passes)
 * - **Use terminate()** - Safe inter-iteration cancellation (sets flag, exits loop)
 * - **Check train.terminated()** - Detects when train has completed its journey
 *
 * ## Example Test Scenario
 *
 * ```kotlin
 * @Test
 * fun `train accelerates from rest`() {
 *     // Arrange
 *     val context = TestTopologies.simpleLinearPathSimulation()
 *     val timetable = createTimetable(...)
 *     val train = Train(context, timetable)
 *
 *     // Act - Run simulation with SimpleTestProcess
 *     val testProcess = SimpleTestProcess(context, train, endTime = 5.0)
 *     context.setMainProcess(testProcess)
 *     context.run()
 *
 *     // Assert - Fine-grained validation after simulation
 *     val finalVelocity = train.getVelocity()
 *     assertThat(finalVelocity).isGreaterThan(0.0)
 *     assertThat(finalVelocity).isLessThanOrEqualTo(80.0) // Track speed limit
 * }
 * ```
 *
 * @param env Simulation environment (context providing simulation facilities)
 * @param train Train instance to coordinate
 * @param endTime Maximum simulation time in seconds
 *
 * @since 2026-02-05 (TrainMovementIntegrationTest re-enablement)
 * @see LoopProcess Base pattern for all loop-based processes
 * @see Generator Similar pattern for train generation
 * @see SimulationExecutionTest Integration test patterns
 */
class SimpleTestProcess(
	private val env: SimulationEnvironment,
	private val train: Train,
	private val endTime: Double
) : LoopProcess() {

	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Called once when Process starts.
	 *
	 * This method activates the train, transitioning it from PASSIVE to ACTIVE state.
	 * The train's actions() method will then begin executing.
	 */
	override fun startAction() {
		logger.debug { "SimpleTestProcess: Starting test with train ${train.name}" }
		activate(train)  // CRITICAL: activate the train process
	}

	/**
	 * Called repeatedly until terminate() is called.
	 *
	 * This method checks if the train has completed its journey or if the
	 * maximum simulation time has been exceeded. If either condition is met,
	 * the process terminates.
	 */
	override fun iteration() {
		val currentTime = time()
		val isTerminated = train.terminated()

		logger.debug {
			"SimpleTestProcess: iteration at t=$currentTime - train terminated: $isTerminated"
		}

		if (isTerminated || currentTime >= endTime) {
			terminate()  // Signal loop to exit
			return
		}
	}

	/**
	 * Called between iterations to advance simulation time.
	 *
	 * This method uses hold() to advance the simulation clock by 1 second,
	 * allowing the train to make progress between checks.
	 */
	override fun interLoopSleep() {
		hold(1.0)  // Check every 1 second of simulation time
	}

	/**
	 * Called once when Process terminates.
	 *
	 * This method logs the completion of the test and can be used for cleanup
	 * if needed.
	 */
	override fun byTerminateAction() {
		logger.debug { "SimpleTestProcess: Test completed at t=${time()}" }
	}

	/**
	 * Test hook for retrieving train state after simulation.
	 *
	 * This method provides access to the train's current state for validation
	 * in test assertions.
	 *
	 * @return TrainState snapshot containing velocity, position, and termination status
	 */
	fun getTrainState(): TrainState = TrainState(
		velocity = train.getVelocity(),
		position = train.frontPosition,
		terminated = train.terminated()
	)

	/**
	 * Snapshot of train state at a specific simulation time.
	 *
	 * @property velocity Train velocity in m/s
	 * @property position Train front position in meters
	 * @property terminated True if train has completed its journey
	 */
	data class TrainState(
		val velocity: Double,
		val position: Double,
		val terminated: Boolean
	)
}
