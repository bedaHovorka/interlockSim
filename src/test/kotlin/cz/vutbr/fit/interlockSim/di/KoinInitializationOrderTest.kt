/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.di

import cz.vutbr.fit.interlockSim.Main
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Tests for Koin initialization order in Main class
 *
 * This test verifies that the editingContextFactory property in Main.kt
 * uses lazy initialization to avoid accessing Koin before it's initialized.
 *
 * ISSUE: PR #63 Review Comment 2680882535
 * The editingContextFactory property must not access Koin's inject() or get()
 * until after startKoin() is called in Main.main().
 *
 * SOLUTION: Use lazy initialization to defer Koin access
 * - Property declared as: `by lazy { get(EditingContextFactory::class.java) }`
 * - This ensures get() is only called when the property is first accessed
 * - Which happens after Koin is initialized in main()
 *
 * @see Main
 * @see EditingContextFactory
 */
class KoinInitializationOrderTest {

	@AfterEach
	fun tearDown() {
		// Clean up Koin context if it was started
		if (GlobalContext.getOrNull() != null) {
			stopKoin()
		}
	}

	/**
	 * Verify that Main.getInstance() can be called before Koin is initialized
	 *
	 * This test ensures that creating the Main singleton instance doesn't
	 * trigger Koin injection. The lazy property should not access Koin
	 * until it's actually used.
	 */
	@Test
	fun `Main instance can be created before Koin initialization`() {
		// Ensure Koin is NOT initialized - expect IllegalStateException (Koin 3.5.6)
		assertFailsWith<IllegalStateException> {
			// This should fail if Koin is not initialized
			org.koin.java.KoinJavaComponent.get(EditingContextFactory::class.java)
		}

		// Getting Main instance should not throw, even though Koin is not initialized
		// This works because editingContextFactory is lazy
		assertDoesNotThrow {
			val mainInstance = Main.getInstance()
			assertNotNull(mainInstance)
		}
	}

	/**
	 * Verify that accessing the context factory after Koin initialization works
	 *
	 * This test simulates the normal application flow:
	 * 1. Koin is initialized in main()
	 * 2. Main methods access editingContextFactory
	 * 3. The lazy property initializes successfully
	 */
	@Test
	fun `context factory accessible after Koin initialization`() {
		// Initialize Koin as done in Main.main()
		startKoin {
			modules(interlockSimModule)
		}

		// Get Main instance
		val mainInstance = Main.getInstance()
		assertNotNull(mainInstance)

		// Access the context factory (which triggers lazy initialization)
		assertDoesNotThrow {
			val factory = mainInstance.getContextFactory()
			assertNotNull(factory)
		}
	}

	/**
	 * Verify that the application can start without premature Koin access
	 *
	 * This test simulates the critical path:
	 * 1. Class loading creates Main.instance
	 * 2. main() starts Koin
	 * 3. Methods access editingContextFactory
	 */
	@Test
	fun `application startup sequence works correctly`() {
		// Step 1: Main class is loaded (this happens when we reference it)
		// The singleton instance is created at this point
		val mainClass = Main::class.java
		assertNotNull(mainClass)

		// At this point, Main.instance exists but editingContextFactory is not yet initialized
		// because it's lazy

		// Step 2: Koin is initialized (as in main())
		startKoin {
			modules(interlockSimModule)
		}

		// Step 3: Access a method that uses editingContextFactory
		assertDoesNotThrow {
			val mainInstance = Main.getInstance()
			val factory = mainInstance.getContextFactory()
			assertNotNull(factory)
		}
	}
}
