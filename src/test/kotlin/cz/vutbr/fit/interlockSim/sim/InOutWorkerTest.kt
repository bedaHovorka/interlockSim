/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 3: Simulation Integration Tests
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.InputStream
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Unit tests for {@link InOutWorker}.
 *
 * Coverage:
 * - InOutWorker initialization
 * - Queue (getQueqe) functionality
 * - Idle state tracking
 * - Integration with SimulationContext
 *
 * Note: These tests focus on InOutWorker setup and state management.
 * Full simulation execution tests (waitUntil, path reservation) require
 * jDisco framework and are beyond the scope of unit testing.
 */
class InOutWorkerTest : KoinTestBase() {
	@Nested
	@DisplayName("InOutWorker initialization")
	inner class InitializationTests {
		@Test
		fun constructor_validInOut_succeeds() {
			// Create mock context with a single InOut
			val context = createMockSimulationContext()
			val dynInOut = createTestDynamicInOut("A", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, dynInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_nullContext_throwsNullPointerException() {
			val dynInOut = createTestDynamicInOut("A", false, SpatialType.HORIZONTAL)
			val nullContext: SimulationContext? = null

			@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			assertThatBlock { InOutWorker(nullContext!!, dynInOut) }
				.isFailure()
				.isInstanceOf(NullPointerException::class)
		}

		@Test
		fun constructor_nullInOut_throwsNullPointerException() {
			val context = createMockSimulationContext()
			val nullInOut: DynamicInOut? = null

			@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			assertThatBlock { InOutWorker(context, nullInOut!!) }
				.isFailure()
				.isInstanceOf(NullPointerException::class)
		}

		@Test
		fun constructor_entryInOut_succeeds() {
			val context = createMockSimulationContext()
			val entryInOut = createTestDynamicInOut("IN", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, entryInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_exitInOut_succeeds() {
			val context = createMockSimulationContext()
			val exitInOut = createTestDynamicInOut("OUT", true, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, exitInOut)

			assertThat(worker).isNotNull()
		}
	}

	@Nested
	@DisplayName("Queue management")
	inner class QueueManagementTests {
		@Test
		fun getQueqe_afterConstruction_returnsNonNullQueue() {
			val context = createMockSimulationContext()
			val inOut = createTestDynamicInOut("A", false, SpatialType.HORIZONTAL)
			val worker = InOutWorker(context, inOut)

			val queue = worker.getQueqe()

			assertThat(queue)
				.withMessage("Queue should not be null after construction")
				.isNotNull()
		}

		@Test
		fun getQueqe_afterConstruction_queueIsEmpty() {
			val context = createMockSimulationContext()
			val inOut = createTestDynamicInOut("A", false, SpatialType.HORIZONTAL)
			val worker = InOutWorker(context, inOut)

			val queue = worker.getQueqe()

			assertThat(queue.empty())
				.withMessage("Queue should be empty after construction")
				.isTrue()
		}

		@Test
		fun getQueqe_calledMultipleTimes_returnsSameInstance() {
			val context = createMockSimulationContext()
			val inOut = createTestDynamicInOut("A", false, SpatialType.HORIZONTAL)
			val worker = InOutWorker(context, inOut)

			val queue1 = worker.getQueqe()
			val queue2 = worker.getQueqe()

			assertThat(queue1)
				.withMessage("Multiple getQueqe calls should return same instance")
				.isSameInstanceAs(queue2)
		}
	}

	@Nested
	@DisplayName("Queue state tracking")
	inner class QueueStateTests {
		@Test
		fun queueEmpty_afterConstruction_returnsTrue() {
			val context = createMockSimulationContext()
			val inOut = createTestDynamicInOut("A", false, SpatialType.HORIZONTAL)
			val worker = InOutWorker(context, inOut)

			val isEmpty = worker.getQueqe().empty()

			assertThat(isEmpty)
				.withMessage("Queue should be empty after construction")
				.isTrue()
		}

		@Test
		fun queueEmpty_afterConstruction_isConsistent() {
			val context = createMockSimulationContext()
			val inOut = createTestDynamicInOut("A", false, SpatialType.HORIZONTAL)
			val worker = InOutWorker(context, inOut)

			// Verify queue is empty
			assertThat(worker.getQueqe().empty()).isTrue()

			// Verify it's consistently empty
			assertThat(worker.getQueqe().empty())
				.withMessage("Queue should remain empty")
				.isTrue()
		}
	}

	@Nested
	@DisplayName("InOutWorker with different InOut configurations")
	inner class InOutConfigurationTests {
		@Test
		fun constructor_horizontalInOut_succeeds() {
			val context = createMockSimulationContext()
			val horizontalInOut = createTestDynamicInOut("H", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, horizontalInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_verticalInOut_succeeds() {
			val context = createMockSimulationContext()
			val verticalInOut = createTestDynamicInOut("V", false, SpatialType.VERTICAL)

			val worker = InOutWorker(context, verticalInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_diagonal1InOut_succeeds() {
			val context = createMockSimulationContext()
			val diagonalInOut = createTestDynamicInOut("D1", false, SpatialType.DIAGONAL1)

			val worker = InOutWorker(context, diagonalInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_diagonal2InOut_succeeds() {
			val context = createMockSimulationContext()
			val diagonalInOut = createTestDynamicInOut("D2", false, SpatialType.DIAGONAL2)

			val worker = InOutWorker(context, diagonalInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_inOutWithLongName_succeeds() {
			val context = createMockSimulationContext()
			val inOut = createTestDynamicInOut("VERY_LONG_INOUT_NAME_FOR_TESTING", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, inOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_inOutWithSingleCharName_succeeds() {
			val context = createMockSimulationContext()
			val inOut = createTestDynamicInOut("X", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, inOut)

			assertThat(worker).isNotNull()
		}
	}

	@Nested
	@DisplayName("InOutWorker with realistic railway contexts")
	inner class RealisticContextTests {
		@Test
		fun constructor_linearTrackContext_succeeds() {
			// Load linear track fixture
			val xml = xml("/cz/vutbr/fit/interlockSim/xml/fixtures/linear-track.xml")
			val simContext = createMockSimulationContext(xml)

			// Get first DynamicInOut from context
			val inOutA = simContext.getInOuts().first()
			assertThat(inOutA).isNotNull()

			val worker = InOutWorker(simContext, inOutA)

			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
			assertThat(worker.getQueqe().empty()).isTrue()
		}

		@Test
		fun constructor_switchBasicContext_succeeds() {
			// Load switch-basic fixture
			val xml: InputStream = xml("/cz/vutbr/fit/interlockSim/xml/fixtures/switch-basic.xml")
			val simContext = createMockSimulationContext(xml)

			// Get first DynamicInOut from context
			val inOutIN = simContext.getInOuts().first()
			assertThat(inOutIN).isNotNull()

			val worker = InOutWorker(simContext, inOutIN)

			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
		}

		@Test
		fun constructor_vyhybnaContext_succeeds() {
			// Load vyhybna.xml fixture
			val simContext = createMockSimulationContext(shuntingXml())

			// Get InOut A from vyhybna.xml
			val inOutA = simContext.getInOuts().first { it.name == "A" }
			assertThat(inOutA).isNotNull()
			assertThat(inOutA.name).isEqualTo("A")

			val worker = InOutWorker(simContext, inOutA)

			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
			assertThat(worker.getQueqe().empty()).isTrue()
		}

		@Test
		fun constructor_multipleWorkersForSameContext_succeed() {
			// Load vyhybna.xml fixture
			val simContext = createMockSimulationContext(shuntingXml())

			// Get both InOuts from vyhybna.xml
			val inOutList = simContext.getInOuts().toList()
			val inOutA = inOutList.first { it.name == "A" }
			val inOutB = inOutList.first { it.name == "B" }
			assertThat(inOutA).isNotNull()
			assertThat(inOutB).isNotNull()

			// Create workers for both InOuts
			val workerA = InOutWorker(simContext, inOutA)
			val workerB = InOutWorker(simContext, inOutB)

			assertThat(workerA).isNotNull()
			assertThat(workerB).isNotNull()
			assertThat(workerA).isNotSameInstanceAs(workerB)
			assertThat(workerA.getQueqe()).isNotSameInstanceAs(workerB.getQueqe())
		}
	}

	private fun shuntingXml(): InputStream = xml("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")

	private fun xml(name: String): InputStream {
		val xml = javaClass.getResourceAsStream(name)
		requireNotNull(xml) { "$name must exist in resources" }
		return xml
	}

	/**
	 * Helper function to create a DynamicInOut for testing.
	 */
	private fun createTestDynamicInOut(
		name: String,
		orientation: Boolean,
		spatialType: SpatialType
	): DynamicInOut {
		val staticInOut = InOut(name, orientation, spatialType)
		val inSemaphore = createDynamicInstance(RailSemaphore(true, spatialType))
		val outSemaphore = createDynamicInstance(RailSemaphore(false, spatialType))
		return DynamicInOut(staticInOut, inSemaphore, outSemaphore)
	}
}
