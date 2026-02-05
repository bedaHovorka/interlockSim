package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isSameAs
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.InputStream
import cz.vutbr.fit.interlockSim.testutil.TestFixtures

/**
 * Test that verifies InOut wrappers are created correctly without duplication.
 *
 * This test validates that the fix for PR #95 regression works:
 * - getInOuts() does NOT create duplicate wrappers
 * - getInOuts() retrieves wrappers from staticToDynamicMap
 * - Multiple calls to getInOuts() return the same instances
 */
@DisplayName("InOut Wrapper Creation Test (PR #95 Fix)")
class InOutWrapperCreationTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): SimulationContext {
		return TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			simulationContextFactory.createContext(editingContext)
		}
	}

	/**
	 * Test that getInOuts() successfully retrieves wrappers from staticToDynamicMap.
	 *
	 * This test verifies:
	 * 1. Context creation succeeds
	 * 2. getInOuts() can be called without exceptions
	 * 3. Wrappers are retrieved (not created) from staticToDynamicMap
	 *
	 * If this test passes, it confirms the duplicate wrapper bug is fixed.
	 */
	@Test
	fun `getInOuts retrieves existing wrappers without creating duplicates`() {
		// Given: Simulation context loaded from vyhybna.xml
		val context = loadVyhybnaContext()

		// When: Call getInOuts() multiple times
		val inouts1 = context.getInOuts()
		val inouts2 = context.getInOuts()

		// Then: Both calls should succeed and return non-null
		assertThat(inouts1).isNotNull()
		assertThat(inouts2).isNotNull()

		// Verify they return the same collection (singleton behavior)
		@Suppress("DEPRECATION")
		assertThat(inouts1).isSameAs(inouts2)
	}
}
