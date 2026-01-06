/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import java.awt.Point;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType;
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection;
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder;
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory;

/**
 * Comprehensive unit tests for {@link DefaultContext}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Grid operations: putCell, removeCell, moveCell</li>
 *   <li>Track navigation: getNextTrackBlock, getNextTrackSection</li>
 *   <li>Path operations: pathToNextSemaphore, isSeparatorInDirection</li>
 *   <li>Configuration: maxSpeed, trackLength settings</li>
 *   <li>Report management: addReportTypes, isReporting</li>
 * </ul>
 */
@DisplayName("DefaultContext")
public class DefaultContextTest {

	@Nested
	@DisplayName("Grid Operations")
	class GridOperationsTests {

		private DefaultContext context;

		@BeforeEach
		void setUp() {
			context = XMLContextFactory.getInstance().createEmptyContext();
		}

		@Test
		@DisplayName("putCell at valid position adds cell to grid")
		void putCell_validPosition_addsCell() {
			// Arrange
			Point position = new Point(5, 5);
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);

			// Act
			context.putCell(position, inOut);

			// Assert
			Cell retrievedCell = context.getRailWayNetGrid().getCellAt(5, 5);
			assertThat(retrievedCell).isSameAs(inOut);
		}

		@Test
		@DisplayName("putCell at same position twice replaces cell")
		void putCell_samePlaceTwice_replacesCell() {
			// Arrange
			Point position = new Point(5, 5);
			InOut first = new InOut("A", false, SpatialType.HORIZONTAL);
			InOut second = new InOut("B", true, SpatialType.HORIZONTAL);

			// Act
			context.putCell(position, first);
			context.putCell(position, second);

			// Assert
			Cell retrievedCell = context.getRailWayNetGrid().getCellAt(5, 5);
			assertThat(retrievedCell).isSameAs(second).isNotSameAs(first);
		}

		@Test
		@DisplayName("removeCell from valid position removes cell")
		void removeCell_existingCell_removes() {
			// Arrange
			Point position = new Point(5, 5);
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);
			context.putCell(position, inOut);

			// Act
			context.removeCell(position);

			// Assert
			Cell retrievedCell = context.getRailWayNetGrid().getCellAt(5, 5);
			assertThat(retrievedCell).isNull();
		}

		@Test
		@DisplayName("removeCell from empty position does not throw")
		void removeCell_emptyPosition_doesNotThrow() {
			// Arrange
			Point position = new Point(5, 5);

			// Act & Assert
			assertThatCode(() -> context.removeCell(position))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("moveCell moves cell from one position to another")
		void moveCell_validPositions_movesCell() {
			// Arrange
			Point from = new Point(5, 5);
			Point to = new Point(10, 10);
			InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);
			context.putCell(from, inOut);

			// Act
			context.moveCell(from, to);

			// Assert
			assertThat(context.getRailWayNetGrid().getCellAt(5, 5))
				.as("Original position should be empty")
				.isNull();
			assertThat(context.getRailWayNetGrid().getCellAt(10, 10))
				.as("New position should contain cell")
				.isSameAs(inOut);
		}

		@Test
		@DisplayName("getRailWayNetGrid returns non-null grid")
		void getRailWayNetGrid_returnsNonNullGrid() {
			// Act
			DefaultRailWayNetGrid grid = context.getRailWayNetGrid();

			// Assert
			assertThat(grid).isNotNull();
		}
	}

	@Nested
	@DisplayName("Track Navigation")
	class TrackNavigationTests {

		private DefaultContext context;
		private InOut inA;
		private InOut outB;
		private RailSemaphore rs1;
		private SimpleTrackBlock tl;

		@BeforeEach
		void setUp() throws Exception {
			context = XMLContextFactory.getInstance().createEmptyContext();
			inA = new InOut("A", false, SpatialType.HORIZONTAL);
			outB = new InOut("B", true, SpatialType.HORIZONTAL);
			rs1 = new RailSemaphore(false, SpatialType.DIAGONAL1);
			tl = new SimpleTrackBlock(inA, outB, 1000, 80);

			Point pA = new Point(1, 1);
			Point r1 = new Point(4, 2);
			Point pB = new Point(5, 5);

			context.putCell(pA, inA);
			context.putCell(pB, outB);
			context.putCell(r1, rs1);
			context.joinCells(r1, pB, tl);
			context.joinCells(pA, r1, tl);
		}

		@Test
		@DisplayName("getNextTrackBlock from InOut with null returns connected block")
		void getNextTrackBlock_fromInOutWithNull_returnsTrackBlock() {
			assertThat(context.getNextTrackBlock(inA, null)).isSameAs(tl);
			assertThat(context.getNextTrackBlock(outB, null)).isSameAs(tl);
		}

		@Test
		@DisplayName("getNextTrackBlock from InOut with current block returns null")
		void getNextTrackBlock_fromInOutWithBlock_returnsNull() {
			assertThat(context.getNextTrackBlock(inA, tl)).isNull();
			assertThat(context.getNextTrackBlock(outB, tl)).isNull();
		}

		@Test
		@DisplayName("getNextTrackSection from separator returns connected section")
		void getNextTrackSection_validSeparator_returnsSection() {
			assertThat(context.getNextTrackSection(inA, null)).isSameAs(tl);
			assertThat(context.getNextTrackSection(outB, null)).isSameAs(tl);
		}

		@Test
		@DisplayName("getNextTrackSection with current section returns null")
		void getNextTrackSection_withCurrentSection_returnsNull() {
			assertThat(context.getNextTrackSection(inA, tl)).isNull();
			assertThat(context.getNextTrackSection(outB, tl)).isNull();
		}

		@Test
		@DisplayName("isSeparatorInDirection validates direction correctly")
		void isSeparatorInDirection_validDirections_returnsTrue() {
			assertThat(context.isSeparatorInDirection(inA, null, tl)).isTrue();
			assertThat(context.isSeparatorInDirection(outB, null, tl)).isTrue();
			assertThat(context.isSeparatorInDirection(inA, tl, null)).isTrue();
			assertThat(context.isSeparatorInDirection(outB, tl, null)).isTrue();
		}
	}

	@Nested
	@DisplayName("Path Operations")
	class PathOperationsTests {

		private DefaultContext context;
		private InOut inA;
		private InOut outB;
		private RailSemaphore rs1;
		private SimpleTrackBlock tl;

		@BeforeEach
		void setUp() throws Exception {
			context = XMLContextFactory.getInstance().createEmptyContext();
			inA = new InOut("A", false, SpatialType.HORIZONTAL);
			outB = new InOut("B", true, SpatialType.HORIZONTAL);
			rs1 = new RailSemaphore(false, SpatialType.DIAGONAL1);
			tl = new SimpleTrackBlock(inA, outB, 1000, 80);

			Point pA = new Point(1, 1);
			Point r1 = new Point(4, 2);
			Point pB = new Point(5, 5);

			context.putCell(pA, inA);
			context.putCell(pB, outB);
			context.putCell(r1, rs1);
			context.joinCells(r1, pB, tl);
			context.joinCells(pA, r1, tl);
		}

		@Test
		@DisplayName("pathToNextSemaphore returns valid path")
		void pathToNextSemaphore_validPath_returnsPath() {
			// Arrange
			ArrayPath expectedPath = new ArrayPath(context);
			expectedPath.addAll(inA, tl, outB);

			// Act & Assert
			assertThat(expectedPath.equalsWithElements(context.pathToNextSemaphore(inA, tl)))
				.as("Path from inA should match expected")
				.isTrue();
			assertThat(expectedPath.reversePath().equalsWithElements(context.pathToNextSemaphore(outB, tl)))
				.as("Path from outB should match expected reversed")
				.isTrue();
		}

		@Test
		@DisplayName("pathToNextSemaphore with null section throws exception")
		void pathToNextSemaphore_nullSection_throwsException() {
			assertThatThrownBy(() -> context.pathToNextSemaphore(inA, null))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> context.pathToNextSemaphore(outB, null))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("Configuration Management")
	class ConfigurationTests {

		private DefaultContext context;

		@BeforeEach
		void setUp() {
			context = XMLContextFactory.getInstance().createEmptyContext();
		}

		@Test
		@DisplayName("setCurrentMaxSpeed updates max speed setting")
		void setCurrentMaxSpeed_validValue_updates() {
			// Arrange
			double newSpeed = 120.0;

			// Act
			context.setCurrentMaxSpeed(newSpeed);

			// Assert
			assertThat(context.getCurrentMaxSpeed()).isEqualTo(newSpeed);
		}

		@Test
		@DisplayName("setCurrentTrackLength updates track length setting")
		void setCurrentTrackLength_validValue_updates() {
			// Arrange
			double newLength = 500.0;

			// Act
			context.setCurrentTrackLength(newLength);

			// Assert
			assertThat(context.getCurrentTrackLength()).isEqualTo(newLength);
		}

		@Test
		@DisplayName("setCurrentNameString updates context name")
		void setCurrentNameString_validName_updates() {
			// Arrange
			String name = "Test Network";

			// Act
			context.setCurrentNameString(name);

			// Assert
			assertThat(context.getCurrentNameString()).isEqualTo(name);
		}
	}

	@Nested
	@DisplayName("Report Management")
	class ReportManagementTests {

		private DefaultContext context;

		@BeforeEach
		void setUp() {
			context = XMLContextFactory.getInstance().createEmptyContext();
		}

		@Test
		@DisplayName("addReportTypes enables reporting for specified types")
		void addReportTypes_validTypes_enablesReporting() {
			// Act
			context.addReportTypes(SimulationContext.ReportType.PATH_SETTING, SimulationContext.ReportType.NODE_EVENTS);

			// Assert
			assertThat(context.isReporting(SimulationContext.ReportType.PATH_SETTING))
				.as("PATH_SETTING should be enabled")
				.isTrue();
			assertThat(context.isReporting(SimulationContext.ReportType.NODE_EVENTS))
				.as("NODE_EVENTS should be enabled")
				.isTrue();
		}

		@Test
		@DisplayName("removeReportTypes disables reporting for specified types")
		void removeReportTypes_previouslyEnabled_disablesReporting() {
			// Arrange
			context.addReportTypes(SimulationContext.ReportType.PATH_SETTING, SimulationContext.ReportType.NODE_EVENTS);

			// Act
			context.removeReportTypes(SimulationContext.ReportType.PATH_SETTING);

			// Assert
			assertThat(context.isReporting(SimulationContext.ReportType.PATH_SETTING))
				.as("PATH_SETTING should be disabled")
				.isFalse();
			assertThat(context.isReporting(SimulationContext.ReportType.NODE_EVENTS))
				.as("NODE_EVENTS should still be enabled")
				.isTrue();
		}

		@Test
		@DisplayName("isReporting returns false for never-enabled type")
		void isReporting_neverEnabled_returnsFalse() {
			// Act & Assert
			assertThat(context.isReporting(SimulationContext.ReportType.TRAIN_CONTINUOUS))
				.as("TRAIN_CONTINUOUS should not be enabled by default")
				.isFalse();
		}
	}

	@Nested
	@DisplayName("TestContextBuilder Integration")
	class TestContextBuilderIntegrationTests {

		@Test
		@DisplayName("buildLinearTrack creates valid context")
		void buildLinearTrack_createsValidContext() {
			// Act
			DefaultContext context = TestContextBuilder.buildLinearTrack();

			// Assert
			assertThat(context).isNotNull();
			assertThat(context.getRailWayNetGrid().getCellAt(1, 1))
				.as("InOut A should exist at (1,1)")
				.isInstanceOf(InOut.class);
			assertThat(context.getRailWayNetGrid().getCellAt(5, 5))
				.as("InOut B should exist at (5,5)")
				.isInstanceOf(InOut.class);
		}

		@Test
		@DisplayName("buildLinearTrackWithSemaphore includes semaphore")
		void buildLinearTrackWithSemaphore_includesSemaphore() {
			// Act
			DefaultContext context = TestContextBuilder.buildLinearTrackWithSemaphore();

			// Assert
			assertThat(context).isNotNull();
			Cell semaphoreCell = context.getRailWayNetGrid().getCellAt(4, 2);
			assertThat(semaphoreCell)
				.as("Semaphore should exist at (4,2)")
				.isInstanceOf(RailSemaphore.class);
		}

		@Test
		@DisplayName("buildMinimal creates single InOut context")
		void buildMinimal_createsSingleInOut() {
			// Act
			DefaultContext context = TestContextBuilder.buildMinimal();

			// Assert
			assertThat(context).isNotNull();
			assertThat(context.getRailWayNetGrid().getCellAt(1, 1))
				.as("InOut A should exist at (1,1)")
				.isInstanceOf(InOut.class);
		}

		@Test
		@DisplayName("fluent API creates custom context")
		void fluentAPI_customContext_works() {
			// Act
			DefaultContext context = new TestContextBuilder()
				.withInOut("Entry", 2, 2, false)
				.withSemaphore(5, 5, true)
				.withInOut("Exit", 8, 8, true)
				.build();

			// Assert
			assertThat(context).isNotNull();
			assertThat(context.getRailWayNetGrid().getCellAt(2, 2)).isInstanceOf(InOut.class);
			assertThat(context.getRailWayNetGrid().getCellAt(5, 5)).isInstanceOf(RailSemaphore.class);
			assertThat(context.getRailWayNetGrid().getCellAt(8, 8)).isInstanceOf(InOut.class);
		}
	}
}
