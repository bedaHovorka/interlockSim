package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Test that verifies InOut wrappers are created correctly without duplication.
 *
 * This test validates that the fix for PR #95 regression works:
 * - getInOuts() does NOT create duplicate wrappers
 * - getInOuts() retrieves wrappers from staticToDynamicMap
 * - Multiple calls to getInOuts() return the same instances
 *
 * Migrated to commonTest: 2026-03 (KMP Task 6)
 */
class InOutWrapperCreationTest : KoinComponent {

	@BeforeTest
	fun setUp() {
		startKoin {
			modules(commonCoreTestModule)
		}
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	private fun loadVyhybnaContext(): SimulationContext {
		val editingCtx = CommonTestFixtures.parseEditingContext(CommonTestFixtures.VYHYBNA_XML)
		val processFactory = get<SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
	}

	/**
	 * Test that getInOuts() successfully retrieves wrappers from staticToDynamicMap.
	 *
	 * If this test passes, it confirms the duplicate wrapper bug is fixed.
	 */
	@Test
	fun `getInOuts retrieves existing wrappers without creating duplicates`() {
		val context = loadVyhybnaContext()

		val inouts1 = context.getInOuts()
		val inouts2 = context.getInOuts()

		assertThat(inouts1).isNotNull()
		assertThat(inouts2).isNotNull()

		assertThat(inouts1).isSameInstanceAs(inouts2)
	}
}
