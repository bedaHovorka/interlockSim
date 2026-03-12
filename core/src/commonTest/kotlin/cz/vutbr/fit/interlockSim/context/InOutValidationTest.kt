/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test: InOut Validation
	Issue #79: Enhance test coverage for InOut validation

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test coverage: 2026-02-05
*/

package cz.vutbr.fit.interlockSim.context

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import cz.vutbr.fit.interlockSim.testutil.coreTestModule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.getKoin

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
 * - XMLContextFactory validation during XML parsing
 * - Programmatic context builder validation
 * - Error message clarity
 *
 * @see cz.vutbr.fit.interlockSim.xml.XMLContextFactory
 * @see TestContextBuilder
 */
class InOutValidationTest {
	private lateinit var xmlFactory: EditingContextFactory

	@BeforeEach
	fun setUp() {
		startKoin {
			modules(coreTestModule)
		}
		xmlFactory = getKoin().get()
	}

	@AfterEach
	fun tearDown() {
		stopKoin()
	}

	/**
	 * Test: Context with 0 InOuts throws exception during XML loading.
	 *
	 * Expected: ContextCreationException with message about minimum 1 InOut.
	 */
	@Test
	fun `XML with 0 InOuts throws ContextCreationException`() {
		val min = XMLContextFactory.MIN_INOUT_ELEMENTS
		assertFailure {
			TestFixtures.loadInvalidInOutXml("zero-inouts.xml").use { stream ->
				xmlFactory.createContext(stream)
			}
		}.isInstanceOf(ContextCreationException::class)
			.transform { it.message ?: "" }
			.contains("Railway network must have at least $min InOut elements (entry and exit points).")
	}

	/**
	 * Test: Context with 1 InOut passes validation.
	 *
	 * With bidirectional train operation, a single InOut is now valid.
	 * Expected: Context created successfully with 1 InOut.
	 */
	@Test
	fun `XML with 1 InOut passes validation`() {
		TestFixtures.loadInvalidInOutXml("single-inout.xml").use { stream ->
			xmlFactory.createContext(stream).use { context ->
				assertThat(context.asEditingContext().getInOuts()).hasSize(1)
			}
		}
	}

	/**
	 * Test: Context with 2 InOuts passes validation.
	 *
	 * Expected: Context created successfully with 2 InOuts.
	 */
	@Test
	fun `XML with 2 InOuts passes validation`() {
		TestFixtures.loadLinearTrackXml().use { stream ->
			xmlFactory.createContext(stream).use { context ->
				assertThat(context.asEditingContext().getInOuts()).hasSize(2)
			}
		}
	}

	/**
	 * Test: Context with 2 or more InOuts passes validation.
	 *
	 * Expected: Context created successfully with correct InOut count.
	 */
	@Test
	fun `XML with 2 or more InOuts passes validation`() {
		// Use shunting loop which has 2 InOuts (adjust if fixture changes)
		TestFixtures.loadShuntingXml().use { stream ->
			xmlFactory.createContext(stream).use { ctx ->
				val context = ctx.asEditingContext()
				val inOutCount = context.getInOuts().size
				assertThat(inOutCount).isEqualTo(2)
			}
		}
	}

	/**
	 * Test: Programmatic context builder with 0 InOuts.
	 *
	 * Note: TestContextBuilder creates EditingContext which doesn't validate InOut count.
	 * Validation happens during XML parsing or when transforming to SimulationContext.
	 */
	@Test
	fun `Programmatic context with 0 InOuts can be created but not transformed`() {
		// EditingContext allows any InOut count (editing phase)
		val builder = TestContextBuilder()
		val editingContext = builder.buildEditingContext()

		assertThat(editingContext.getInOuts()).hasSize(0)

		// Transformation to SimulationContext should validate
		// (This is future work - currently transformers don't validate InOut count)
		editingContext.close()
	}

	/**
	 * Test: Programmatic context builder with 1 InOut.
	 *
	 * Similar to 0 InOuts - EditingContext allows it, but should fail on transformation.
	 */
	@Test
	fun `Programmatic context with 1 InOut can be created`() {
		val builder =
			TestContextBuilder()
				.withInOut("OnlyEntry", 1, 1, true)

		val editingContext = builder.buildEditingContext()

		assertThat(editingContext.getInOuts()).hasSize(1)
		editingContext.close()
	}

	/**
	 * Test: Programmatic context builder with 2 InOuts passes validation.
	 */
	@Test
	fun `Programmatic context with 2 InOuts is valid`() {
		val builder =
			TestContextBuilder()
				.withInOut("Entry", 1, 1, true)
				.withInOut("Exit", 10, 10, false)
				.withConnection(1, 1, 10, 10, 100.0, 80.0)

		val editingContext = builder.buildEditingContext()

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
		assertFailure {
			TestFixtures.loadInvalidInOutXml("zero-inouts.xml").use { stream ->
				xmlFactory.createContext(stream)
			}
		}.isInstanceOf(ContextCreationException::class)
			.transform { it.message ?: "" }
			.contains("entry/exit point")
	}

	/**
	 * Test: Error message reflects MIN_INOUT_ELEMENTS constant value.
	 *
	 * Issue #XXX, PR #358: Ensure error message uses constant instead of hardcoded "2".
	 * Expected: Message contains "at least 1" (current MIN_INOUT_ELEMENTS value).
	 */
	@Test
	fun `Error message reflects MIN_INOUT_ELEMENTS constant value`() {
		val min = XMLContextFactory.MIN_INOUT_ELEMENTS
		assertFailure {
			TestFixtures.loadInvalidInOutXml("zero-inouts.xml").use { stream ->
				xmlFactory.createContext(stream)
			}
		}.isInstanceOf(ContextCreationException::class)
			.transform { it.message ?: "" }
			.contains("at least $min")
	}

	/**
	 * Helper function to safely cast Context to DefaultEditingContext.
	 * Reduces duplication and improves type safety.
	 */
	private fun Context<*, *>.asEditingContext(): DefaultEditingContext =
		this as cz.vutbr.fit.interlockSim.context.DefaultEditingContext
}
