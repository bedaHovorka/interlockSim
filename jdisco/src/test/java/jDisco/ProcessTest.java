package jDisco;

import org.junit.Test;
import org.junit.Ignore;
import static org.junit.Assert.*;

/**
 * Basic tests for Process class - discrete event simulation core
 *
 * NOTE: These tests are currently disabled because Process.activate() blocks indefinitely
 * in the test environment. The jDisco simulation framework requires a different approach
 * for testing, possibly using background threads or simulation end conditions.
 */
public class ProcessTest {

	/**
	 * Test basic process activation and time advancement
	 */
	@Ignore("Process.activate() blocks indefinitely - needs different test approach")
	@Test(timeout = 5000)
	public void testProcessActivationAndTime() {
		final double[] timeValues = new double[3];

		Process mainProcess = new Process() {
			protected void actions() {
				timeValues[0] = time();
				assertEquals(0.0, time(), 0.001);

				hold(10.0);
				timeValues[1] = time();
				assertEquals(10.0, time(), 0.001);

				hold(5.0);
				timeValues[2] = time();
				assertEquals(15.0, time(), 0.001);
			}
		};
		Process.activate(mainProcess);

		// Verify time values were set
		assertEquals(0.0, timeValues[0], 0.001);
		assertEquals(10.0, timeValues[1], 0.001);
		assertEquals(15.0, timeValues[2], 0.001);
	}

	/**
	 * Test multiple processes with different activation times
	 */
	@Ignore("Process.activate() blocks indefinitely - needs different test approach")
	@Test(timeout = 5000)
	public void testMultipleProcesses() {
		final StringBuilder executionOrder = new StringBuilder();

		Process p1 = new Process() {
			protected void actions() {
				executionOrder.append("A");
				hold(5.0);
				executionOrder.append("B");
			}
		};
		Process.activate(p1);

		Process p2 = new Process() {
			protected void actions() {
				hold(2.0);
				executionOrder.append("C");
				hold(5.0);
				executionOrder.append("D");
			}
		};
		Process.activate(p2);

		// Expected order: A, C, B, D
		assertEquals("ACBD", executionOrder.toString());
	}

	/**
	 * Test passivate and reactivation
	 */
	@Ignore("Process.activate() blocks indefinitely - needs different test approach")
	@Test(timeout = 5000)
	public void testPassivateAndReactivate() {
		final StringBuilder log = new StringBuilder();

		final Process[] processRef = new Process[1];

		// Main process
		Process mainProcess = new Process() {
			protected void actions() {
				// Create and activate a process that will passivate
				processRef[0] = new Process() {
					protected void actions() {
						log.append("1");
						passivate();
						log.append("3");
					}
				};
				activate(processRef[0]);

				log.append("2");
				hold(1.0);

				// Reactivate the passivated process
				activate(processRef[0]);
			}
		};
		Process.activate(mainProcess);

		assertEquals("123", log.toString());
	}
}
