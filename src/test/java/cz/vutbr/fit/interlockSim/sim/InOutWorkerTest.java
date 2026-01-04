/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 3: Simulation Integration Tests
 */
package cz.vutbr.fit.interlockSim.sim;

import cz.vutbr.fit.interlockSim.context.DefaultContext;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext;
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
		void constructor_validInOut_succeeds() {
			// Create mock context with a single InOut
			MockSimulationContext context = new MockSimulationContext();
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);

			InOutWorker worker = new InOutWorker(context, inOut);

			assertThat(worker).isNotNull();
		}

		@Test
		void constructor_nullContext_throwsNullPointerException() {
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);

			assertThatThrownBy(() -> new InOutWorker(null, inOut))
				.as("Null context should throw NullPointerException")
				.isInstanceOf(NullPointerException.class);
		}

		@Test
		void constructor_nullInOut_throwsNullPointerException() {
			MockSimulationContext context = new MockSimulationContext();

			assertThatThrownBy(() -> new InOutWorker(context, null))
				.as("Null InOut should throw NullPointerException")
				.isInstanceOf(NullPointerException.class);
		}

		@Test
		void constructor_entryInOut_succeeds() {
			MockSimulationContext context = new MockSimulationContext();
			InOut entryInOut = new InOut("IN", false, SpatialType.HORIZONTAL);

			InOutWorker worker = new InOutWorker(context, entryInOut);

			assertThat(worker).isNotNull();
		}

		@Test
		void constructor_exitInOut_succeeds() {
			MockSimulationContext context = new MockSimulationContext();
			InOut exitInOut = new InOut("OUT", true, SpatialType.HORIZONTAL);

			InOutWorker worker = new InOutWorker(context, exitInOut);

			assertThat(worker).isNotNull();
		}
	}

	@Nested
	@DisplayName("Queue management")
	class QueueManagementTests {

		@Test
		void getQueqe_afterConstruction_returnsNonNullQueue() {
			MockSimulationContext context = new MockSimulationContext();
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);
			InOutWorker worker = new InOutWorker(context, inOut);

			jDisco.Head queue = worker.getQueqe();

			assertThat(queue)
				.as("Queue should not be null after construction")
				.isNotNull();
		}

		@Test
		void getQueqe_afterConstruction_queueIsEmpty() {
			MockSimulationContext context = new MockSimulationContext();
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);
			InOutWorker worker = new InOutWorker(context, inOut);

			jDisco.Head queue = worker.getQueqe();

			assertThat(queue.empty())
				.as("Queue should be empty after construction")
				.isTrue();
		}

		@Test
		void getQueqe_calledMultipleTimes_returnsSameInstance() {
			MockSimulationContext context = new MockSimulationContext();
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);
			InOutWorker worker = new InOutWorker(context, inOut);

			jDisco.Head queue1 = worker.getQueqe();
			jDisco.Head queue2 = worker.getQueqe();

			assertThat(queue1)
				.as("Multiple getQueqe calls should return same instance")
				.isSameAs(queue2);
		}
	}

	@Nested
	@DisplayName("Queue state tracking")
	class QueueStateTests {

		@Test
		void queueEmpty_afterConstruction_returnsTrue() {
			MockSimulationContext context = new MockSimulationContext();
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);
			InOutWorker worker = new InOutWorker(context, inOut);

			boolean isEmpty = worker.getQueqe().empty();

			assertThat(isEmpty)
				.as("Queue should be empty after construction")
				.isTrue();
		}

		@Test
		void queueEmpty_afterConstruction_isConsistent() {
			MockSimulationContext context = new MockSimulationContext();
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);
			InOutWorker worker = new InOutWorker(context, inOut);

			// Verify queue is empty
			assertThat(worker.getQueqe().empty()).isTrue();

			// Verify it's consistently empty
			assertThat(worker.getQueqe().empty())
				.as("Queue should remain empty")
				.isTrue();
		}
	}

	@Nested
	@DisplayName("InOutWorker with different InOut configurations")
	class InOutConfigurationTests {

		@Test
		void constructor_horizontalInOut_succeeds() {
			MockSimulationContext context = new MockSimulationContext();
			InOut horizontalInOut = new InOut("H", false, SpatialType.HORIZONTAL);

			InOutWorker worker = new InOutWorker(context, horizontalInOut);

			assertThat(worker).isNotNull();
		}

		@Test
		void constructor_verticalInOut_succeeds() {
			MockSimulationContext context = new MockSimulationContext();
			InOut verticalInOut = new InOut("V", false, SpatialType.VERTICAL);

			InOutWorker worker = new InOutWorker(context, verticalInOut);

			assertThat(worker).isNotNull();
		}

		@Test
		void constructor_diagonal1InOut_succeeds() {
			MockSimulationContext context = new MockSimulationContext();
			InOut diagonalInOut = new InOut("D1", false, SpatialType.DIAGONAL1);

			InOutWorker worker = new InOutWorker(context, diagonalInOut);

			assertThat(worker).isNotNull();
		}

		@Test
		void constructor_diagonal2InOut_succeeds() {
			MockSimulationContext context = new MockSimulationContext();
			InOut diagonalInOut = new InOut("D2", false, SpatialType.DIAGONAL2);

			InOutWorker worker = new InOutWorker(context, diagonalInOut);

			assertThat(worker).isNotNull();
		}

		@Test
		void constructor_inOutWithLongName_succeeds() {
			MockSimulationContext context = new MockSimulationContext();
			InOut inOut = new InOut("VERY_LONG_INOUT_NAME_FOR_TESTING", false, SpatialType.HORIZONTAL);

			InOutWorker worker = new InOutWorker(context, inOut);

			assertThat(worker).isNotNull();
		}

		@Test
		void constructor_inOutWithSingleCharName_succeeds() {
			MockSimulationContext context = new MockSimulationContext();
			InOut inOut = new InOut("X", false, SpatialType.HORIZONTAL);

			InOutWorker worker = new InOutWorker(context, inOut);

			assertThat(worker).isNotNull();
		}
	}

	@Nested
	@DisplayName("InOutWorker with realistic railway contexts")
	class RealisticContextTests {

		@Test
		void constructor_linearTrackContext_succeeds() throws Exception {
			// Load linear track fixture
			InputStream xml = InOutWorkerTest.class.getResourceAsStream(
				"/cz/vutbr/fit/interlockSim/xml/fixtures/linear-track.xml");
			DefaultContext context = XMLContextFactory.getInstance().createContext(xml);
			MockSimulationContext simContext = new MockSimulationContext(context);

			// Get InOut A from context
			InOut inOutA = (InOut) context.getRailWayNetGrid().getCellAt(10, 10);
			assertThat(inOutA).isNotNull();

			InOutWorker worker = new InOutWorker(simContext, inOutA);

			assertThat(worker).isNotNull();
			assertThat(worker.getQueqe()).isNotNull();
			assertThat(worker.getQueqe().empty()).isTrue();
		}

		@Test
		void constructor_switchBasicContext_succeeds() throws Exception {
			// Load switch-basic fixture
			InputStream xml = InOutWorkerTest.class.getResourceAsStream(
				"/cz/vutbr/fit/interlockSim/xml/fixtures/switch-basic.xml");
			DefaultContext context = XMLContextFactory.getInstance().createContext(xml);
			MockSimulationContext simContext = new MockSimulationContext(context);

			// Get InOut IN from context
			InOut inOutIN = (InOut) context.getRailWayNetGrid().getCellAt(10, 10);
			assertThat(inOutIN).isNotNull();

			InOutWorker worker = new InOutWorker(simContext, inOutIN);

			assertThat(worker).isNotNull();
			assertThat(worker.getQueqe()).isNotNull();
		}

		@Test
		void constructor_vyhybnaContext_succeeds() throws Exception {
			// Load vyhybna.xml fixture
			InputStream xml = InOutWorkerTest.class.getResourceAsStream(
				"/cz/vutbr/fit/interlockSim/resource/vyhybna.xml");
			DefaultContext context = XMLContextFactory.getInstance().createContext(xml);
			MockSimulationContext simContext = new MockSimulationContext(context);

			// Get InOut A from vyhybna.xml (at position 11, 8)
			InOut inOutA = (InOut) context.getRailWayNetGrid().getCellAt(11, 8);
			assertThat(inOutA).isNotNull();
			assertThat(inOutA.getName()).isEqualTo("A");

			InOutWorker worker = new InOutWorker(simContext, inOutA);

			assertThat(worker).isNotNull();
			assertThat(worker.getQueqe()).isNotNull();
			assertThat(worker.getQueqe().empty()).isTrue();
		}

		@Test
		void constructor_multipleWorkersForSameContext_succeed() throws Exception {
			// Load vyhybna.xml fixture
			InputStream xml = InOutWorkerTest.class.getResourceAsStream(
				"/cz/vutbr/fit/interlockSim/resource/vyhybna.xml");
			DefaultContext context = XMLContextFactory.getInstance().createContext(xml);
			MockSimulationContext simContext = new MockSimulationContext(context);

			// Get both InOuts from vyhybna.xml
			InOut inOutA = (InOut) context.getRailWayNetGrid().getCellAt(11, 8);
			InOut inOutB = (InOut) context.getRailWayNetGrid().getCellAt(30, 8);
			assertThat(inOutA).isNotNull();
			assertThat(inOutB).isNotNull();

			// Create workers for both InOuts
			InOutWorker workerA = new InOutWorker(simContext, inOutA);
			InOutWorker workerB = new InOutWorker(simContext, inOutB);

			assertThat(workerA).isNotNull();
			assertThat(workerB).isNotNull();
			assertThat(workerA).isNotSameAs(workerB);
			assertThat(workerA.getQueqe()).isNotSameAs(workerB.getQueqe());
		}
	}
}
