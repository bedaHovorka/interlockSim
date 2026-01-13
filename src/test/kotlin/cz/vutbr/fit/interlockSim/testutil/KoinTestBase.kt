/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Base class for tests using Koin dependency injection
 */
package cz.vutbr.fit.interlockSim.testutil

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest

/**
 * Base class for tests that use Koin dependency injection.
 *
 * Automatically starts Koin before each test and stops it after each test,
 * eliminating boilerplate setup/teardown code in individual test classes.
 *
 * Usage:
 * ```kotlin
 * class MyTest : KoinTestBase() {
 *     private val factory: XMLContextFactory by inject()
 *
 *     @Test
 *     fun myTest() {
 *         // factory is available here
 *     }
 * }
 * ```
 *
 * Note: This class handles Koin lifecycle management for tests that need to
 * access injected properties in @BeforeEach methods. KoinTest alone doesn't
 * start Koin until test methods run, so accessing `by inject()` properties
 * in @BeforeEach would fail without this base class.
 *
 * @since 2026-01-12 (Koin migration)
 */
abstract class KoinTestBase : KoinTest {
	@BeforeEach
	fun setUpKoin() {
		startKoin {
			modules(testModule)
		}
	}

	@AfterEach
	fun tearDownKoin() {
		stopKoin()
	}
}
