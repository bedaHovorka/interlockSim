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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.saveAndReloadThroughFile
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Name-attribute tests for [XMLContextFactory]: name persistence across
 * save/load, name validation rules, and backward compatibility (Issue #306).
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class XMLContextFactoryNameAttributeTest : XMLContextFactoryTestBase() {
	@Test
	@DisplayName("save and load preserves RailSemaphore names")
	fun saveAndLoad_preservesRailSemaphoreNames() {
		val context = editingContextFactory.createEmptyContext()
		val semaphore = RailSemaphore("signal_test_1", true, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(10, 10), semaphore)
		val inOut1 = InOut("entry", true, Cell.SpatialType.HORIZONTAL)
		val inOut2 = InOut("exit", false, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(5, 10), inOut1)
		context.putCell(Point(15, 10), inOut2)

		val tempFile = File.createTempFile("test-semaphore-names-", ".xml")
		tempFile.deleteOnExit()

		context.use {
			// Save, load back, and verify the semaphore name is preserved
			editingContextFactory.saveAndReloadThroughFile(it, tempFile) { loadedContext ->
				val loadedSemaphore = loadedContext.getRailWayNetGrid().getCellAt(10, 10) as RailSemaphore
				assertThat(loadedSemaphore.getName()).isEqualTo("signal_test_1")
			}
		}

		tempFile.delete()
	}

	@Test
	@DisplayName("save and load preserves RailSwitch names")
	fun saveAndLoad_preservesRailSwitchNames() {
		val context = editingContextFactory.createEmptyContext()
		val railSwitch = RailSwitch("switch_A", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_TRUE)
		context.putCell(Point(10, 10), railSwitch)
		val inOut1 = InOut("entry", true, Cell.SpatialType.HORIZONTAL)
		val inOut2 = InOut("exit", false, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(5, 10), inOut1)
		context.putCell(Point(15, 10), inOut2)

		val tempFile = File.createTempFile("test-switch-names-", ".xml")
		tempFile.deleteOnExit()

		context.use {
			// Save, load back, and verify the switch name is preserved
			editingContextFactory.saveAndReloadThroughFile(it, tempFile) { loadedContext ->
				val loadedSwitch = loadedContext.getRailWayNetGrid().getCellAt(10, 10) as RailSwitch
				assertThat(loadedSwitch.getName()).isEqualTo("switch_A")
			}
		}

		tempFile.delete()
	}

	@Test
	@DisplayName("load XML without name attributes (backward compatibility)")
	fun parseXML_withoutNameAttributes_succeedsWithAutoNames() {
		val xml = getFixtureStream("legacy-network-no-names.xml")

		editingContextFactory.createContext(xml).use { context ->
			assertThat(context).isNotNull()
			// Elements without names should have empty names (auto-naming happens in GUI)
			val semaphore = context.getRailWayNetGrid().getCellAt(15, 10) as RailSemaphore
			val railSwitch = context.getRailWayNetGrid().getCellAt(20, 10) as RailSwitch
			assertThat(semaphore.getName()).isEmpty()
			assertThat(railSwitch.getName()).isEmpty()
		}
	}

	@Test
	@DisplayName("empty string names are omitted in serialization")
	fun saveContext_withEmptyNames_omitsNameAttribute() {
		val context = editingContextFactory.createEmptyContext()
		val semaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL) // name defaults to empty
		context.putCell(Point(10, 10), semaphore)
		val inOut1 = InOut("entry", true, Cell.SpatialType.HORIZONTAL)
		val inOut2 = InOut("exit", false, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(5, 10), inOut1)
		context.putCell(Point(15, 10), inOut2)

		val tempFile = File.createTempFile("test-empty-names-", ".xml")
		tempFile.deleteOnExit()

		context.use {
			assertThat(editingContextFactory.saveContext(it, tempFile), name = "saveContext(file) must succeed").isTrue()

			// Read XML and verify name attribute is not present
			val xmlContent = tempFile.readText()
			assertThat(xmlContent.contains("RailSemaphore")).isTrue()
			// Empty name should NOT be serialized (no name="" in output)
			assertThat(xmlContent.contains("name=\"\"")).isFalse()
		}

		tempFile.delete()
	}

	@Test
	@DisplayName("valid special characters in names")
	fun parseXML_validSpecialChars_succeeds() {
		val xml = getFixtureStream("valid-special-chars-names.xml")

		editingContextFactory.createContext(xml).use { context ->
			assertThat(context).isNotNull()
			val inOut = context.getRailWayNetGrid().getCellAt(10, 10) as InOut
			val semaphore = context.getRailWayNetGrid().getCellAt(15, 10) as RailSemaphore
			val railSwitch = context.getRailWayNetGrid().getCellAt(20, 10) as RailSwitch
			assertThat(inOut.getName()).isEqualTo("entry_1")
			assertThat(semaphore.getName()).isEqualTo("signal-north-001")
			assertThat(railSwitch.getName()).isEqualTo("switch_junction_A")
		}
	}

	@Test
	@DisplayName("names longer than 50 characters fail validation")
	fun parseXML_nameTooLong_throwsException() {
		val xml = getFixtureStream("invalid-name-too-long.xml")

		assertThatBlock { editingContextFactory.createContext(xml) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}

	@Test
	@DisplayName("names with invalid characters fail validation")
	fun parseXML_invalidCharacters_throwsException() {
		val xml = getFixtureStream("invalid-name-special-chars.xml")

		assertThatBlock { editingContextFactory.createContext(xml) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}
}
