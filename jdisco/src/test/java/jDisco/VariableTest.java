package jDisco;

import org.junit.Test;
import org.junit.Ignore;
import static org.junit.Assert.*;

/**
 * Additional tests for Variable class
 */
public class VariableTest {

	/**
	 * Test variable initialization
	 */
	@Test
	public void testVariableInitialization() {
		Variable v1 = new Variable(0.0);
		assertEquals(0.0, v1.state, 0.001);
		assertEquals(0.0, v1.rate, 0.001);

		Variable v2 = new Variable(42.5);
		assertEquals(42.5, v2.state, 0.001);
		assertEquals(0.0, v2.rate, 0.001);

		Variable v3 = new Variable(-10.0);
		assertEquals(-10.0, v3.state, 0.001);
	}

	/**
	 * Test variable state changes
	 */
	@Test
	public void testVariableStateChanges() {
		Variable v = new Variable(10.0);

		v.state = 20.0;
		assertEquals(20.0, v.state, 0.001);

		v.state = v.state + 5.0;
		assertEquals(25.0, v.state, 0.001);
	}

	/**
	 * Test variable rate assignment
	 */
	@Test
	public void testVariableRateAssignment() {
		Variable v = new Variable(0.0);

		v.rate = 1.0;
		assertEquals(1.0, v.rate, 0.001);

		v.rate = -2.5;
		assertEquals(-2.5, v.rate, 0.001);
	}

	/**
	 * Test multiple variables in continuous simulation
	 */
	@Ignore("Process.activate() blocks indefinitely - needs different test approach")
	@Test(timeout = 5000)
	public void testMultipleVariables() {
		final Variable x = new Variable(1.0);
		final Variable y = new Variable(2.0);

		// Define coupled dynamics:
		// dx/dt = y
		// dy/dt = -x
		final Continuous dynamics = new Continuous() {
			protected void derivatives() {
				x.rate = y.state;
				y.rate = -x.state;
			}
		};
		dynamics.start();

		Process mainProcess = new Process() {
			protected void actions() {
				double x0 = x.state;
				double y0 = y.state;

				hold(0.1);

				// Both should have changed
				assertTrue("x changed", Math.abs(x.state - x0) > 0.001);
				assertTrue("y changed", Math.abs(y.state - y0) > 0.001);

				// Stop the continuous process to allow simulation to terminate
				dynamics.stop();
			}
		};
		Process.activate(mainProcess);
	}
}
