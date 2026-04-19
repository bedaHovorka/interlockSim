package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.test.inject
import kotlin.reflect.KClass

@DisplayName("AutoNameGenerator")
class AutoNameGeneratorTest : KoinTestBase() {
	private val editingFactory: EditingContextFactory by inject()
	private lateinit var context: EditingContext

	@BeforeEach
	fun setUp() {
		AutoNameGenerator.reset()
		context = editingFactory.createEmptyContext()
	}

	@AfterEach
	fun tearDown() {
		AutoNameGenerator.reset()
	}

	companion object {
		@JvmStatic
		fun cellTypePrefixes() =
			listOf(
				Arguments.of(RailSemaphore::class, "S1"),
				Arguments.of(RailSwitch::class, "SW1"),
				Arguments.of(InOut::class, "IO1")
			)
	}

	@Nested
	@DisplayName("Name generation")
	inner class NameGeneration {
		@ParameterizedTest(name = "first name for {0} is {1}")
		@MethodSource("cz.vutbr.fit.interlockSim.context.AutoNameGeneratorTest#cellTypePrefixes")
		fun `generates correct first name for cell type`(
			cellClass: KClass<out NodeCell>,
			expectedName: String
		) {
			val name = AutoNameGenerator.generateName(cellClass, context)
			assertThat(name).isEqualTo(expectedName)
		}

		@Test
		fun `generates sequential names for same type`() {
			val name1 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			val name2 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			val name3 = AutoNameGenerator.generateName(RailSemaphore::class, context)

			assertThat(name1).isEqualTo("S1")
			assertThat(name2).isEqualTo("S2")
			assertThat(name3).isEqualTo("S3")
		}

		@Test
		fun `independent counters for different types`() {
			val semaphore1 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			val switch1 = AutoNameGenerator.generateName(RailSwitch::class, context)
			val semaphore2 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			val switch2 = AutoNameGenerator.generateName(RailSwitch::class, context)

			assertThat(semaphore1).isEqualTo("S1")
			assertThat(switch1).isEqualTo("SW1")
			assertThat(semaphore2).isEqualTo("S2")
			assertThat(switch2).isEqualTo("SW2")
		}
	}

	@Nested
	@DisplayName("Uniqueness and conflict resolution")
	inner class Uniqueness {
		@Test
		fun `skips existing names in grid`() {
			// Pre-populate grid with S1 and S2
			addSemaphore(5, 5, "S1")
			addSemaphore(6, 6, "S2")

			// Should skip to S3
			val name = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name).isEqualTo("S3")
		}

		@Test
		fun `handles non-sequential existing names`() {
			addSemaphore(5, 5, "S5")

			// Should start at S1 (counter is 0)
			val name1 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name1).isEqualTo("S1")

			// S2, S3, S4 are free
			val name2 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name2).isEqualTo("S2")
		}

		@Test
		fun `handles mixed existing and generated names`() {
			// Start with S10 in grid
			addSemaphore(5, 5, "S10")

			val name1 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			val name2 = AutoNameGenerator.generateName(RailSemaphore::class, context)

			assertThat(name1).isEqualTo("S1") // Counter starts at 0
			assertThat(name2).isEqualTo("S2") // Counter increments
		}

		@Test
		fun `skips multiple sequential gaps`() {
			addSemaphore(1, 1, "S1")
			addSemaphore(2, 2, "S2")
			addSemaphore(3, 3, "S3")

			val name1 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name1).isEqualTo("S4")

			val name2 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name2).isEqualTo("S5")
		}
	}

	@Nested
	@DisplayName("Performance and caching")
	inner class Performance {
		@Test
		fun `caches names for repeated checks`() {
			// Populate grid with 100 semaphores
			repeat(100) { i ->
				addSemaphore(i % 20, i / 20, "S${i + 1}")
			}

			// Generate one more name - cache should handle this efficiently
			val name = AutoNameGenerator.generateName(RailSemaphore::class, context)

			// Verify functional correctness - cache produces correct sequential name
			assertThat(name).isEqualTo("S101")
		}

		@Test
		fun `rebuilds cache when context changes`() {
			val context1 = editingFactory.createEmptyContext()
			val context2 = editingFactory.createEmptyContext()

			addSemaphoreToContext(context1, 5, 5, "S1")
			addSemaphoreToContext(context2, 5, 5, "S100")

			val name1 = AutoNameGenerator.generateName(RailSemaphore::class, context1)
			val name2 = AutoNameGenerator.generateName(RailSemaphore::class, context2)

			// Should skip S1 in context1, then skip S2 and S100 in context2
			assertThat(name1).isEqualTo("S2")
			assertThat(name2).isEqualTo("S3") // Counter continues from context1, cache rebuilds to avoid S100
		}

		@Test
		fun `cache persists across multiple generations for same context`() {
			// First generation builds cache
			val name1 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name1).isEqualTo("S1")

			// Subsequent generations use cache (no rebuild)
			val name2 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name2).isEqualTo("S2")

			val name3 = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name3).isEqualTo("S3")
		}
	}

	@Nested
	@DisplayName("Reset functionality")
	inner class Reset {
		@Test
		fun `reset clears counters`() {
			AutoNameGenerator.generateName(RailSemaphore::class, context) // S1
			AutoNameGenerator.generateName(RailSemaphore::class, context) // S2

			AutoNameGenerator.reset()

			val name = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name).isEqualTo("S1") // Restarted from 1
		}

		@Test
		fun `reset clears cache`() {
			addSemaphore(5, 5, "S1")
			AutoNameGenerator.generateName(RailSemaphore::class, context) // Builds cache

			// Modify grid directly (bypass cache)
			context.removeCell(Point(5, 5))

			AutoNameGenerator.reset() // Clear cache

			// Should rebuild cache and see S1 is free
			val name = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name).isEqualTo("S1")
		}

		@Test
		fun `reset allows reusing same context`() {
			AutoNameGenerator.generateName(RailSemaphore::class, context) // S1
			AutoNameGenerator.reset()

			// Same context, but reset counter
			val name = AutoNameGenerator.generateName(RailSemaphore::class, context)
			assertThat(name).isEqualTo("S1")
		}
	}

	// Helper methods for test setup

	/**
	 * Adds a semaphore to the test context at the specified position with the given name.
	 */
	private fun addSemaphore(
		x: Int,
		y: Int,
		name: String
	) {
		addSemaphoreToContext(context, x, y, name)
	}

	/**
	 * Adds a semaphore to the specified context at the given position with the given name.
	 */
	private fun addSemaphoreToContext(
		ctx: EditingContext,
		x: Int,
		y: Int,
		name: String
	) {
		val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		semaphore.setName(name)
		ctx.putCell(Point(x, y), semaphore)
	}
}
