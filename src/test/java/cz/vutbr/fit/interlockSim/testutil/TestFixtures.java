/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Test Utility: Fixtures
    Common test data and utility methods

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test infrastructure: 2025
*/

package cz.vutbr.fit.interlockSim.testutil;

/**
 * Common test fixtures and utility methods for unit tests.
 *
 * <p>Provides reusable test data, constants, and helper methods
 * for creating consistent test scenarios across test suites.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * double length = TestFixtures.STANDARD_TRACK_LENGTH;
 * String id = TestFixtures.generateTrackId(1);
 * }</pre>
 */
public class TestFixtures {

	private TestFixtures() {
		// Utility class - prevent instantiation
	}

	// Common test constants
	public static final double STANDARD_TRACK_LENGTH = 100.0;
	public static final double SHORT_TRACK_LENGTH = 50.0;
	public static final double LONG_TRACK_LENGTH = 200.0;

	/**
	 * Generates a standard track ID for testing.
	 *
	 * @param index track number (1-based)
	 * @return track ID string (e.g., "T1", "T2")
	 */
	public static String generateTrackId(int index) {
		return "T" + index;
	}

	/**
	 * Generates a standard switch ID for testing.
	 *
	 * @param index switch number (1-based)
	 * @return switch ID string (e.g., "SW1", "SW2")
	 */
	public static String generateSwitchId(int index) {
		return "SW" + index;
	}

	/**
	 * Generates a standard semaphore ID for testing.
	 *
	 * @param index semaphore number (1-based)
	 * @return semaphore ID string (e.g., "SEM1", "SEM2")
	 */
	public static String generateSemaphoreId(int index) {
		return "SEM" + index;
	}

	// TODO: Add more utility methods as needed during test development
	// - createTemporaryXmlFile()
	// - generateRandomTrackLayout()
	// - assertContextEquals(Context expected, Context actual)
}
