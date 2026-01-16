/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Abstract base class for tests that create Frame GUI components
	Provides proper lifecycle management and resource cleanup
	Phase 4.1 test implementation - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

/**
 * Abstract base class for tests that create Frame GUI components.
 *
 * This class provides proper lifecycle management for Frame instances to prevent:
 * - Resource leaks (undisposed Frame objects)
 * - CI timeout issues (Frame hanging without disposal)
 * - Thread safety issues (GUI operations on non-EDT threads)
 *
 * All tests that extend this class:
 * - Are tagged as @Tag("integration-test") and run separately
 * - Have proper Frame disposal in @AfterEach
 * - Run GUI operations on EDT via SwingUtilities.invokeAndWait
 * - Skip execution in headless environments
 * - Have timeout annotations to prevent indefinite hangs
 *
 * Subclasses should:
 * - Call super.setUp() in their @BeforeEach if they override it
 * - Call super.tearDown() in their @AfterEach if they override it
 * - Store Frame references for proper disposal
 * - Use SwingUtilities.invokeAndWait for all GUI operations
 *
 * Example usage:
 * ```
 * class MyFrameTest : AbstractFrameTestBase() {
 *     private lateinit var frame: Frame
 *
 *     @BeforeEach
 *     override fun setUp() {
 *         super.setUp()
 *         SwingUtilities.invokeAndWait {
 *             frame = Frame()
 *         }
 *     }
 *
 *     @Test
 *     @Timeout(value = 5, unit = TimeUnit.SECONDS)
 *     fun testFrameBehavior() {
 *         // Test code here
 *     }
 * }
 * ```
 *
 * Architecture notes:
 * - Extends KoinTestBase for dependency injection support
 * - Tagged as integration test at class level
 * - Checks for headless environment and skips tests if X11 unavailable
 * - Provides centralized Frame disposal logic
 *
 * GitHub Issue: #111 (CI timeout prevention)
 */
@Tag("integration-test")
abstract class AbstractFrameTestBase : KoinTestBase() {
	/**
	 * List of Frame instances created during test execution.
	 * Subclasses should add Frame references to this list for automatic disposal.
	 */
	protected val frames: MutableList<Frame> = mutableListOf()

	/**
	 * Setup method that checks for headless environment.
	 * Subclasses should call super.setUp() if they override this.
	 */
	@BeforeEach
	open fun setUp() {
		// Check if we're in a headless environment (no X11 display)
		val isHeadless = GraphicsEnvironment.isHeadless()
		Assumptions.assumeFalse(
			isHeadless,
			"Skipping GUI test - headless environment (no X11 display)"
		)
	}

	/**
	 * Teardown method that disposes all Frame instances.
	 * Subclasses should call super.tearDown() if they override this.
	 */
	@AfterEach
	open fun tearDown() {
		// Dispose all frames on EDT to prevent resource leaks
		frames.forEach { frame ->
			try {
				SwingUtilities.invokeAndWait {
					if (frame.isDisplayable) {
						frame.dispose()
					}
				}
			} catch (e: Exception) {
				// Log but don't fail test cleanup
				System.err.println("Warning: Failed to dispose Frame: ${e.message}")
			}
		}
		frames.clear()
	}

	/**
	 * Helper method to create a Frame on EDT and register it for disposal.
	 * @return The created Frame instance
	 */
	protected fun createFrame(): Frame {
		var frame: Frame? = null
		SwingUtilities.invokeAndWait {
			frame = Frame()
		}
		val createdFrame = frame ?: error("Frame creation failed")
		frames.add(createdFrame)
		return createdFrame
	}

	/**
	 * Helper method to safely show a Frame on EDT.
	 * @param frame The Frame to show
	 */
	protected fun showFrame(frame: Frame) {
		SwingUtilities.invokeAndWait {
			frame.isVisible = true
		}
	}

	/**
	 * Helper method to safely hide a Frame on EDT.
	 * @param frame The Frame to hide
	 */
	protected fun hideFrame(frame: Frame) {
		SwingUtilities.invokeAndWait {
			frame.isVisible = false
		}
	}

	/**
	 * Helper method to run code on EDT and wait for completion.
	 * @param block The code to run on EDT
	 */
	protected fun runOnEDT(block: () -> Unit) {
		SwingUtilities.invokeAndWait(block)
	}
}
