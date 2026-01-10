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
import cz.vutbr.fit.interlockSim.context.DefaultContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
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
class InOutWorkerTest {
	@Nested
	@DisplayName("InOutWorker initialization")
	class InitializationTests {
		@Test
		fun constructor_validInOut_succeeds() {
			// Create mock context with a single InOut
			val context = MockSimulationContext()
			val inOut = InOut("A", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, inOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_nullContext_throwsNullPointerException() {
			val inOut = InOut("A", false, SpatialType.HORIZONTAL)
			val nullContext: SimulationContext? = null

			@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			assertThatBlock { InOutWorker(nullContext!!, inOut) }
				.isFailure()
				.isInstanceOf(NullPointerException::class)
		}

		@Test
		fun constructor_nullInOut_throwsNullPointerException() {
			val context = MockSimulationContext()
			val nullInOut: InOut? = null

			@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			assertThatBlock { InOutWorker(context, nullInOut!!) }
				.isFailure()
				.isInstanceOf(NullPointerException::class)
		}

		@Test
		fun constructor_entryInOut_succeeds() {
			val context = MockSimulationContext()
			val entryInOut = InOut("IN", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, entryInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_exitInOut_succeeds() {
			val context = MockSimulationContext()
			val exitInOut = InOut("OUT", true, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, exitInOut)

			assertThat(worker).isNotNull()
		}
	}

	@Nested
	@DisplayName("Queue management")
	class QueueManagementTests {
		@Test
		fun getQueqe_afterConstruction_returnsNonNullQueue() {
			val context = MockSimulationContext()
			val inOut = InOut("A", false, SpatialType.HORIZONTAL)
			val worker = InOutWorker(context, inOut)

			val queue = worker.getQueqe()

			assertThat(queue)
				.withMessage("Queue should not be null after construction")
				.isNotNull()
		}

		@Test
		fun getQueqe_afterConstruction_queueIsEmpty() {
			val context = MockSimulationContext()
			val inOut = InOut("A", false, SpatialType.HORIZONTAL)
			val worker = InOutWorker(context, inOut)

			val queue = worker.getQueqe()

			assertThat(queue.empty())
				.withMessage("Queue should be empty after construction")
				.isTrue()
		}

		@Test
		fun getQueqe_calledMultipleTimes_returnsSameInstance() {
			val context = MockSimulationContext()
			val inOut = InOut("A", false, SpatialType.HORIZONTAL)
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
	class QueueStateTests {
		@Test
		fun queueEmpty_afterConstruction_returnsTrue() {
			val context = MockSimulationContext()
			val inOut = InOut("A", false, SpatialType.HORIZONTAL)
			val worker = InOutWorker(context, inOut)

			val isEmpty = worker.getQueqe().empty()

			assertThat(isEmpty)
				.withMessage("Queue should be empty after construction")
				.isTrue()
		}

		@Test
		fun queueEmpty_afterConstruction_isConsistent() {
			val context = MockSimulationContext()
			val inOut = InOut("A", false, SpatialType.HORIZONTAL)
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
	class InOutConfigurationTests {
		@Test
		fun constructor_horizontalInOut_succeeds() {
			val context = MockSimulationContext()
			val horizontalInOut = InOut("H", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, horizontalInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_verticalInOut_succeeds() {
			val context = MockSimulationContext()
			val verticalInOut = InOut("V", false, SpatialType.VERTICAL)

			val worker = InOutWorker(context, verticalInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_diagonal1InOut_succeeds() {
			val context = MockSimulationContext()
			val diagonalInOut = InOut("D1", false, SpatialType.DIAGONAL1)

			val worker = InOutWorker(context, diagonalInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_diagonal2InOut_succeeds() {
			val context = MockSimulationContext()
			val diagonalInOut = InOut("D2", false, SpatialType.DIAGONAL2)

			val worker = InOutWorker(context, diagonalInOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_inOutWithLongName_succeeds() {
			val context = MockSimulationContext()
			val inOut = InOut("VERY_LONG_INOUT_NAME_FOR_TESTING", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, inOut)

			assertThat(worker).isNotNull()
		}

		@Test
		fun constructor_inOutWithSingleCharName_succeeds() {
			val context = MockSimulationContext()
			val inOut = InOut("X", false, SpatialType.HORIZONTAL)

			val worker = InOutWorker(context, inOut)

			assertThat(worker).isNotNull()
		}
	}

	@Nested
	@DisplayName("InOutWorker with realistic railway contexts")
	class RealisticContextTests {
		@Test
		fun constructor_linearTrackContext_succeeds() {
			// Load linear track fixture
			val xml: InputStream =
				InOutWorkerTest::class.java.getResourceAsStream(
					"/cz/vutbr/fit/interlockSim/xml/fixtures/linear-track.xml"
				)!!
			val context: DefaultContext = XMLContextFactory.getInstance().createContext(xml)
			val simContext = MockSimulationContext(context)

			// Get InOut A from context
			val inOutA = context.getRailWayNetGrid().getCellAt(10, 10) as InOut
			assertThat(inOutA).isNotNull()

			val worker = InOutWorker(simContext, inOutA)

			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
			assertThat(worker.getQueqe().empty()).isTrue()
		}

		@Test
		fun constructor_switchBasicContext_succeeds() {
			// Load switch-basic fixture
			val xml: InputStream =
				InOutWorkerTest::class.java.getResourceAsStream(
					"/cz/vutbr/fit/interlockSim/xml/fixtures/switch-basic.xml"
				)!!
			val context: DefaultContext = XMLContextFactory.getInstance().createContext(xml)
			val simContext = MockSimulationContext(context)

			// Get InOut IN from context
			val inOutIN = context.getRailWayNetGrid().getCellAt(10, 10) as InOut
			assertThat(inOutIN).isNotNull()

			val worker = InOutWorker(simContext, inOutIN)

			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
		}

		@Test
		fun constructor_vyhybnaContext_succeeds() {
			// Load vyhybna.xml fixture
			val xml: InputStream =
				InOutWorkerTest::class.java.getResourceAsStream(
					"/cz/vutbr/fit/interlockSim/resource/vyhybna.xml"
				)!!
			val context: DefaultContext = XMLContextFactory.getInstance().createContext(xml)
			val simContext = MockSimulationContext(context)

			// Get InOut A from vyhybna.xml (at position 11, 8)
			val inOutA = context.getRailWayNetGrid().getCellAt(11, 8) as InOut
			assertThat(inOutA).isNotNull()
			assertThat(inOutA.getName()).isEqualTo("A")

			val worker = InOutWorker(simContext, inOutA)

			assertThat(worker).isNotNull()
			assertThat(worker.getQueqe()).isNotNull()
			assertThat(worker.getQueqe().empty()).isTrue()
		}

		@Test
		fun constructor_multipleWorkersForSameContext_succeed() {
			// Load vyhybna.xml fixture
			val xml: InputStream =
				InOutWorkerTest::class.java.getResourceAsStream(
					"/cz/vutbr/fit/interlockSim/resource/vyhybna.xml"
				)!!
			val context: DefaultContext = XMLContextFactory.getInstance().createContext(xml)
			val simContext = MockSimulationContext(context)

			// Get both InOuts from vyhybna.xml
			val inOutA = context.getRailWayNetGrid().getCellAt(11, 8) as InOut
			val inOutB = context.getRailWayNetGrid().getCellAt(30, 8) as InOut
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
}
