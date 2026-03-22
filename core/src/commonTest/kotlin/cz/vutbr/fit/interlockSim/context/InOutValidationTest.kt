/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test: InOut Validation
	Issue #79: Enhance test coverage for InOut validation

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test coverage: 2026-02-05
	Migrated to commonTest: 2026-03 (KMP Task 6)
*/

package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Test suite for InOut validation rules.
 *
 * **Requirements:**
 * - Minimum 1 InOut element required (entry/exit point)
 * - Networks with 0 InOuts are invalid
 * - Networks with 1+ InOuts are valid
 * - With bidirectional operation, a single InOut can serve as both entry and exit
 *
 * **Coverage:**
 * - XmlContextReader validation during XML parsing
 * - Programmatic context building validation
 * - Error message clarity
 *
 * @see cz.vutbr.fit.interlockSim.xml.XmlContextReader
 */
class InOutValidationTest {

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

	/**
	 * Test: Context with 0 InOuts throws exception during XML loading.
	 *
	 * Expected: IllegalStateException with message about minimum 1 InOut.
	 */
	@Test
	fun `XML with 0 InOuts throws IllegalStateException`() {
		val exception = assertFailsWith<IllegalStateException> {
			CommonTestFixtures.parseEditingContext(NetworkResources.ZERO_INOUTS_XML)
		}
		assertThat(exception.message ?: "").transform { it.contains("at least 1") }
			.isEqualTo(true)
	}

	/**
	 * Test: Context with 1 InOut passes validation.
	 *
	 * With bidirectional train operation, a single InOut is now valid.
	 * Expected: Context created successfully with 1 InOut.
	 */
	@Test
	fun `XML with 1 InOut passes validation`() {
		val context = CommonTestFixtures.parseEditingContext(NetworkResources.SINGLE_INOUT_XML)
		assertThat(context.getInOuts()).hasSize(1)
		context.close()
	}

	/**
	 * Test: Context with 2 InOuts passes validation.
	 *
	 * Expected: Context created successfully with 2 InOuts.
	 */
	@Test
	fun `XML with 2 InOuts passes validation`() {
		val context = CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML)
		assertThat(context.getInOuts()).hasSize(2)
		context.close()
	}

	/**
	 * Test: Context with 2 or more InOuts passes validation.
	 *
	 * Expected: Context created successfully with correct InOut count.
	 */
	@Test
	fun `XML with 2 or more InOuts passes validation`() {
		val context = CommonTestFixtures.parseEditingContext(NetworkResources.VYHYBNA_XML)
		val inOutCount = context.getInOuts().size
		assertThat(inOutCount).isEqualTo(2)
		context.close()
	}

	/**
	 * Test: Programmatic context builder with 0 InOuts.
	 *
	 * Note: DefaultEditingContext allows any InOut count (editing phase).
	 * Validation happens during XML parsing or when transforming to SimulationContext.
	 */
	@Test
	fun `Programmatic context with 0 InOuts can be created but not transformed`() {
		val editingContext = DefaultEditingContext(30, 30)
		assertThat(editingContext.getInOuts()).hasSize(0)
		editingContext.close()
	}

	/**
	 * Test: Programmatic context builder with 1 InOut.
	 */
	@Test
	fun `Programmatic context with 1 InOut can be created`() {
		val editingContext = DefaultEditingContext(30, 30)
		val inOut = InOut("OnlyEntry", true, Cell.SpatialType.HORIZONTAL)
		editingContext.putCell(Point(1, 1), inOut)

		assertThat(editingContext.getInOuts()).hasSize(1)
		editingContext.close()
	}

	/**
	 * Test: Programmatic context builder with 2 InOuts passes validation.
	 */
	@Test
	fun `Programmatic context with 2 InOuts is valid`() {
		val editingContext = DefaultEditingContext(30, 30)
		val entry = InOut("Entry", true, Cell.SpatialType.HORIZONTAL)
		val exit = InOut("Exit", false, Cell.SpatialType.HORIZONTAL)
		editingContext.putCell(Point(1, 1), entry)
		editingContext.putCell(Point(10, 10), exit)

		assertThat(editingContext.getInOuts()).hasSize(2)
		editingContext.close()
	}

	/**
	 * Test: Error message contains helpful explanation.
	 *
	 * Expected: Exception message explains why 1 InOut is required.
	 */
	@Test
	fun `Error message explains InOut requirement clearly`() {
		val exception = assertFailsWith<IllegalStateException> {
			CommonTestFixtures.parseEditingContext(NetworkResources.ZERO_INOUTS_XML)
		}
		assertThat(exception.message ?: "").transform { it.contains("entry") }
			.isEqualTo(true)
	}

	/**
	 * Test: Error message reflects MIN_INOUT_ELEMENTS constant value.
	 *
	 * Expected: Message contains "at least 1" (current MIN_INOUT_ELEMENTS value).
	 */
	@Test
	fun `Error message reflects MIN_INOUT_ELEMENTS constant value`() {
		val exception = assertFailsWith<IllegalStateException> {
			CommonTestFixtures.parseEditingContext(NetworkResources.ZERO_INOUTS_XML)
		}
		assertThat(exception.message ?: "").transform { it.contains("at least 1") }
			.isEqualTo(true)
	}
}
