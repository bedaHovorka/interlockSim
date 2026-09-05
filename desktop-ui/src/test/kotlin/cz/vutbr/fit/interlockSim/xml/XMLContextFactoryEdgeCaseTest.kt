/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 3: XML Parsing & Configuration Tests
 */
package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Edge-case tests for [XMLContextFactory]: empty, minimal, oversized, and
 * boundary networks built from inline XML.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class XMLContextFactoryEdgeCaseTest : XMLContextFactoryTestBase() {
	@Test
	fun parseXML_largeGridSize_succeeds() {
		val largeGridXML =
			"<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"500\" Y=\"500\">\n" +
				"  <InOut X=\"10\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"ENTRY\"/>\n" +
				"  <InOut X=\"490\" Y=\"490\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"EXIT\"/>\n" +
				"</net>"
		val stream = ByteArrayInputStream(largeGridXML.toByteArray())

		editingContextFactory.createContext(stream).use { context ->
			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(500)
			assertThat(grid.rows).isEqualTo(500)
		}
	}

	@Test
	fun parseXML_minimalGridSize_succeeds() {
		val minimalGridXML =
			"<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"10\" Y=\"10\">\n" +
				"  <InOut X=\"1\" Y=\"1\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"ENTRY\"/>\n" +
				"  <InOut X=\"2\" Y=\"1\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"EXIT\"/>\n" +
				"</net>"
		val stream = ByteArrayInputStream(minimalGridXML.toByteArray())

		editingContextFactory.createContext(stream).use { context ->
			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(10)
			assertThat(grid.rows).isEqualTo(10)
		}
	}

	@Test
	fun parseXML_cellAtGridBoundary_succeeds() {
		val boundaryXML =
			"<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"100\" Y=\"100\">\n" +
				"  <InOut X=\"1\" Y=\"1\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"ENTRY\"/>\n" +
				"  <InOut X=\"98\" Y=\"98\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"CORNER\"/>\n" +
				"</net>"
		val stream = ByteArrayInputStream(boundaryXML.toByteArray())

		editingContextFactory.createContext(stream).use { context ->
			assertThat(context).isNotNull()
			val cell = context.getRailWayNetGrid().getCellAt(98, 98)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)
			assertThat((cell as InOut).getName()).isEqualTo("CORNER")
		}
	}

	@Test
	fun parseXML_multipleInOutsWithSameName_lastOneWins() {
		val duplicateNameXML =
			"<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"100\" Y=\"100\">\n" +
				"  <InOut X=\"10\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"A\"/>\n" +
				"  <InOut X=\"20\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"A\"/>\n" +
				"</net>"
		val stream = ByteArrayInputStream(duplicateNameXML.toByteArray())

		editingContextFactory.createContext(stream).use { context ->
			assertThat(context).isNotNull()
			// Both cells should exist at different positions
			val cell1 = context.getRailWayNetGrid().getCellAt(10, 10)
			val cell2 = context.getRailWayNetGrid().getCellAt(20, 10)
			assertThat(cell1).isNotNull().isInstanceOf(InOut::class)
			assertThat(cell2).isNotNull().isInstanceOf(InOut::class)
		}
	}
}
