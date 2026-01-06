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

package cz.vutbr.fit.interlockSim.testutil

/**
 * Common test fixtures and utility methods for unit tests.
 *
 * Provides reusable test data, constants, and helper methods
 * for creating consistent test scenarios across test suites.
 *
 * Example usage:
 * ```
 * Double length = TestFixtures.STANDARD_TRACK_LENGTH
 * String id = TestFixtures.generateTrackId(1)
 * ```
 */
object TestFixtures {
	// Common test constants
	const val STANDARD_TRACK_LENGTH = 100.0
	const val SHORT_TRACK_LENGTH = 50.0
	const val LONG_TRACK_LENGTH = 200.0

	/**
	 * Generates a standard track ID for testing.
	 *
	 * @param index track number (1-based)
	 * @return track ID string (e.g., "T1", "T2")
	 */
	fun generateTrackId(index: Int): String = "T" + index

	/**
	 * Generates a standard switch ID for testing.
	 *
	 * @param index switch number (1-based)
	 * @return switch ID string (e.g., "SW1", "SW2")
	 */
	fun generateSwitchId(index: Int): String = "SW" + index

	/**
	 * Generates a standard semaphore ID for testing.
	 *
	 * @param index semaphore number (1-based)
	 * @return semaphore ID string (e.g., "SEM1", "SEM2")
	 */
	fun generateSemaphoreId(index: Int): String = "SEM" + index

	// TODO: Add more utility methods as needed during test development
	// - createTemporaryXmlFile()
	// - generateRandomTrackLayout()
	// - assertContextEquals(Context expected, Context actual)
}
