package jDisco;

import org.junit.Test;
import org.junit.Ignore;
import static org.junit.Assert.*;

/**
 * Basic tests for Continuous and Variable classes
 *
 * NOTE: Tests involving Process.activate() are disabled because it blocks indefinitely
 * in the test environment. The jDisco simulation framework requires a different approach
 * for testing continuous processes.
 */
public class ContinuousTest {

	/**
	 * Test simple continuous variable integration
	 */
	@Ignore("Process.activate() blocks indefinitely - needs different test approach")
	@Test(timeout = 5000)
	public void testSimpleContinuousIntegration() {
		final Variable x = new Variable(1.0);

		// Define continuous dynamics: dx/dt = -0.1*x
		final Continuous dynamics = new Continuous() {
			protected void derivatives() {
				x.rate = -0.1 * x.state;
			}
		};
		dynamics.start();

		// Run simulation
		Process mainProcess = new Process() {
			protected void actions() {
				double initialValue = x.state;
				assertEquals(1.0, initialValue, 0.001);

				hold(1.0);

				// After 1 time unit, x should have decreased
				assertTrue("x should decrease over time", x.state < initialValue);

				// Rough check: exponential decay
				// x(t) ≈ x(0) * e^(-0.1*t)
				// x(1) ≈ 1.0 * e^(-0.1) ≈ 0.905
				assertTrue("x should be approximately 0.905",
						Math.abs(x.state - 0.905) < 0.1);

				// Stop the continuous process to allow simulation to terminate
				dynamics.stop();
			}
		};
		Process.activate(mainProcess);
	}

	/**
	 * Test Variable state and rate
	 */
	@Test
	public void testVariableStateAndRate() {
		Variable v = new Variable(42.0);
		assertEquals(42.0, v.state, 0.001);
		assertEquals(0.0, v.rate, 0.001);  // Default rate is 0

		v.rate = 10.0;
		assertEquals(10.0, v.rate, 0.001);
	}

	/**
	 * Test start and stop of continuous process
	 */
	@Ignore("Process.activate() blocks indefinitely - needs different test approach")
	@Test(timeout = 5000)
	public void testContinuousStartStop() {
		final Variable x = new Variable(1.0);

		final Continuous dynamics = new Continuous() {
			protected void derivatives() {
				x.rate = 1.0;  // dx/dt = 1
			}
		};

		Process mainProcess = new Process() {
			protected void actions() {
				// Initially not active
				assertFalse(dynamics.isActive());

				// Start continuous process
				dynamics.start();
				assertTrue(dynamics.isActive());

				hold(1.0);

				// Stop continuous process
				dynamics.stop();
				assertFalse(dynamics.isActive());
			}
		};
		Process.activate(mainProcess);
	}
}
