/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: start and stop a simulation through the Frame, from a test thread
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.gui.Frame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Hands [context] to this frame, starts the simulation, waits until it is really running, runs
 * [body], then always stops the simulation and closes the context.
 *
 * Around twenty frame tests spelled this sequence out: arm a [CountDownLatch] on a property-change
 * listener, `invokeAndWait { setContext(); startSimulation() }`, await the latch, do the actual
 * assertion, `invokeAndWait { stopSimulation() }`, `context.close()` (Issue #955, cluster U3).
 *
 * The waiting matters. `startSimulation()` returns as soon as the simulation thread is launched, so
 * a test that asserts immediately afterwards races the thread it is testing. The latch is released
 * by the first property change the context publishes, which is the first evidence the run is live.
 *
 * Anything that must be in place **before** the run starts — a mock declared into `context.scope`,
 * a listener registered on the frame — belongs before this call, not inside [body].
 *
 * @param context the context to run; closed when this returns, however it returns
 * @param startTimeoutSeconds how long to wait for the first property change
 * @throws AssertionError when the simulation does not start within [startTimeoutSeconds]
 */
fun <T> Frame.withStartedSimulation(
	context: SimulationContext,
	startTimeoutSeconds: Long = 5L,
	body: () -> T
): T {
	val started = CountDownLatch(1)
	context.addPropertyChangeListener { _ -> started.countDown() }
	try {
		SwingUtilities.invokeAndWait {
			setContext(context)
			startSimulation()
		}
		assertThat(started.await(startTimeoutSeconds, TimeUnit.SECONDS)).isTrue()
		return body()
	} finally {
		SwingUtilities.invokeAndWait { stopSimulation() }
		context.close()
	}
}
