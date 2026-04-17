/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Base class for tests using Koin dependency injection (commonTest / multiplatform version)
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.Context
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

/**
 * Base class for commonTest tests that use Koin dependency injection.
 *
 * kotlin.test-native equivalent of the JVM-only [KoinTestBase] (which uses
 * JUnit5 @BeforeEach/@AfterEach). Subclasses can override [getTestModule] to
 * supply a different Koin module; the default is [commonCoreTestModule].
 *
 * Uses [KoinComponent] instead of koin-test's KoinTest so no additional
 * dependency beyond koin-core is required in commonMain.
 *
 * @since 2026 (commonTest migration — Task 3c)
 */
abstract class CommonKoinTestBase : KoinComponent {

	/**
	 * Optional context tracking for automatic cleanup.
	 * Tests that create a context in @BeforeTest should assign this field.
	 * It is closed automatically in [tearDownKoin].
	 */
	protected var testContext: Context<*, *>? = null

	/**
	 * Override this method to use a different test module.
	 * Default: [commonCoreTestModule]
	 */
	protected open fun getTestModule(): Module = commonCoreTestModule

	@BeforeTest
	fun setUpKoin() {
		startKoin {
			modules(getTestModule())
		}
		afterKoinSetUp()
	}

	/**
	 * Hook called after Koin is started. Subclasses that need to initialize
	 * fields using injected dependencies should override this method instead
	 * of annotating their own setUp with [@BeforeTest], since on Kotlin/Native
	 * the ordering of [@BeforeTest] between superclass and subclass is undefined.
	 */
	protected open fun afterKoinSetUp() {}

	@AfterTest
	fun tearDownKoin() {
		testContext?.close()
		testContext = null
		stopKoin()
	}
}
