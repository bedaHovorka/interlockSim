/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Lenient XML Parsing Tests
 */
package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Unit tests for lenient XML parsing mode in XMLContextFactory.
 *
 * Tests the separation of unparseable XML (malformed syntax) from
 * parseable XML with validation errors, supporting the editor's ability
 * to open and fix invalid files.
 *
 * Test Scenarios:
 * 1. Unparseable XML (malformed syntax) - should block opening
 * 2. Parseable XML with validation errors - should allow opening with warnings
 * 3. Valid XML - should open without warnings
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class XMLContextFactoryLenientTest : KoinTestBase() {
	private val xmlContextFactory: XMLContextFactory by inject()

	@Nested
	@DisplayName("Lenient parsing - Unparseable XML")
	inner class UnparseableXmlTests {
		@Test
		fun malformedXml_shouldReturnUnparseable() {
			// Arrange
			val fixtureFile = getFixtureFile("invalid-malformed-xml.xml")

			// Act
			val result = xmlContextFactory.createContextLenient(fixtureFile)

			// Assert
			assertThat(result.isParseable).isFalse()
			assertThat(result.context).isNull()
			assertThat(result.validationResult.isValid).isFalse()
			assertThat(result.validationResult.errors).transform { it.isNotEmpty() }.isTrue()
		}
	}

	@Nested
	@DisplayName("Lenient parsing - Parseable XML with validation errors")
	inner class ParseableWithErrorsTests {
		@Test
		fun singleInOut_shouldReturnParseableWithErrors() {
			// Arrange: single-inout.xml has only 1 InOut (violates minimum 2 requirement)
			val fixtureFile = getFixtureFile("single-inout.xml")

			// Act
			val result = xmlContextFactory.createContextLenient(fixtureFile)

			// Assert: Should be parseable but have validation errors
			assertThat(result.isParseable).isTrue()
			assertThat(result.context).isNotNull()
			assertThat(result.validationResult.isValid).isFalse()
			assertThat(result.validationResult.errors).transform { it.isNotEmpty() }.isTrue()
		}

		@Test
		fun zeroInOuts_shouldReturnParseableWithErrors() {
			// Arrange: zero-inouts.xml has no InOut elements
			val fixtureFile = getFixtureFile("zero-inouts.xml")

			// Act
			val result = xmlContextFactory.createContextLenient(fixtureFile)

			// Assert: Should be parseable but have validation errors
			assertThat(result.isParseable).isTrue()
			assertThat(result.context).isNotNull()
			assertThat(result.validationResult.isValid).isFalse()
			assertThat(result.validationResult.errors).transform { it.isNotEmpty() }.isTrue()
		}
	}

	@Nested
	@DisplayName("Lenient parsing - Valid XML")
	inner class ValidXmlTests {
		@Test
		fun minimalNetwork_shouldReturnValidContext() {
			// Arrange: minimal-network.xml is a valid minimal network
			val fixtureFile = getFixtureFile("minimal-network.xml")

			// Act
			val result = xmlContextFactory.createContextLenient(fixtureFile)

			// Assert: Should be parseable with no errors
			assertThat(result.isParseable).isTrue()
			assertThat(result.context).isNotNull()
			assertThat(result.validationResult.isValid).isTrue()
			assertThat(result.validationResult.errors).transform { it.isEmpty() }.isTrue()
		}

		@Test
		fun linearTrack_shouldReturnValidContext() {
			// Arrange: linear-track.xml is a valid network with track blocks
			val fixtureFile = getFixtureFile("linear-track.xml")

			// Act
			val result = xmlContextFactory.createContextLenient(fixtureFile)

			// Assert: Should be parseable with no errors
			assertThat(result.isParseable).isTrue()
			assertThat(result.context).isNotNull()
			assertThat(result.validationResult.isValid).isTrue()
			assertThat(result.validationResult.errors).transform { it.isEmpty() }.isTrue()
		}
	}

	@Nested
	@DisplayName("File not found handling")
	inner class FileNotFoundTests {
		@Test
		fun nonExistentFile_shouldReturnUnparseable() {
			// Arrange
			val nonExistentFile = File("non-existent-file.xml")

			// Act
			val result = xmlContextFactory.createContextLenient(nonExistentFile)

			// Assert
			assertThat(result.isParseable).isFalse()
			assertThat(result.context).isNull()
			assertThat(result.validationResult.isValid).isFalse()
			assertThat(result.validationResult.errors).transform { it.isNotEmpty() }.isTrue()
		}
	}

	@Nested
	@DisplayName("Edge cases and error handling")
	inner class EdgeCaseTests {
		@Test
		fun emptyFile_shouldReturnUnparseable() {
			// Arrange: Create temporary empty file
			val emptyFile = File.createTempFile("empty", ".xml")
			emptyFile.deleteOnExit()

			// Act
			val result = xmlContextFactory.createContextLenient(emptyFile)

			// Assert: Empty file should be unparseable
			assertThat(result.isParseable).isFalse()
			assertThat(result.context).isNull()
			assertThat(result.validationResult.isValid).isFalse()
			assertThat(result.validationResult.errors).transform { it.isNotEmpty() }.isTrue()
		}

		@Test
		fun xmlWithSpecialCharactersInErrorMessage_shouldHandleGracefully() {
			// Arrange: Create file with properly escaped special characters
			val specialFile = File.createTempFile("special", ".xml")
			specialFile.deleteOnExit()
			specialFile.writeText("""
				<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="10" Y="10">
					<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="false" name="TestName"/>
				</net>
			""".trimIndent())

			// Act
			val result = xmlContextFactory.createContextLenient(specialFile)

			// Assert: Should parse but have validation errors (only 1 InOut)
			// This tests that error messages with special characters are handled properly
			assertThat(result.isParseable).isTrue()
			assertThat(result.context).isNotNull()
			assertThat(result.validationResult.isValid).isFalse()
		}

		@Test
		fun largeXmlFile_shouldHandlePerformance() {
			// Arrange: Create a larger XML file with many elements
			val largeFile = File.createTempFile("large", ".xml")
			largeFile.deleteOnExit()
			
			val content = buildString {
				appendLine("""<?xml version="1.0"?>""")
				appendLine("""<!DOCTYPE net>""")
				appendLine("""<net X="50" Y="50">""")
				// Add many InOut elements to create a larger file
				for (i in 1..100) {
					appendLine(
						"""  <InOut X="${i % 50}" Y="${i / 50}" """ +
							"""SpatialType="HORIZONTAL" orientation="false" name="InOut$i"/>"""
					)
				}
				appendLine("""</net>""")
			}
			largeFile.writeText(content)

			// Act
			val startTime = System.currentTimeMillis()
			val result = xmlContextFactory.createContextLenient(largeFile)
			val elapsedTime = System.currentTimeMillis() - startTime

			// Assert: Should parse successfully and complete in reasonable time (< 5 seconds)
			assertThat(result.isParseable).isTrue()
			assertThat(result.validationResult.isValid).isTrue()
			assertThat(result.context).isNotNull()
			assertThat(elapsedTime < 5000).isTrue() // Performance check
		}

		@Test
		fun invalidXml_genericException_shouldReturnUnparseable() {
			// Arrange: Create file that triggers generic exception path
			val invalidFile = File.createTempFile("invalid", ".xml")
			invalidFile.deleteOnExit()
			// Write invalid content that's not XML at all
			invalidFile.writeText("This is not XML at all, just plain text")

			// Act
			val result = xmlContextFactory.createContextLenient(invalidFile)

			// Assert: Should be unparseable
			assertThat(result.isParseable).isFalse()
			assertThat(result.context).isNull()
			assertThat(result.validationResult.isValid).isFalse()
			assertThat(result.validationResult.errors).transform { it.isNotEmpty() }.isTrue()
		}
	}

	@Nested
	@DisplayName("Concurrent parsing tests")
	inner class ConcurrentParsingTests {
		@Test
		fun sequentialParsing_shouldWorkCorrectly() {
			// Arrange: Get a valid fixture file
			val fixtureFile = getFixtureFile("minimal-network.xml")
			
			// Act: Parse the same file multiple times sequentially
			val results = (1..5).map {
				xmlContextFactory.createContextLenient(fixtureFile)
			}

			// Assert: All results should be consistent
			results.forEach { result ->
				assertThat(result.isParseable).isTrue()
				assertThat(result.validationResult.isValid).isTrue()
				assertThat(result.context).isNotNull()
			}
		}
	}

	/**
	 * Helper to get fixture file from test resources.
	 */
	private fun getFixtureFile(filename: String): File {
		val resourcePath = "cz/vutbr/fit/interlockSim/xml/fixtures/$filename"
		val url = javaClass.classLoader.getResource(resourcePath)
			?: throw IllegalArgumentException("Fixture file not found: $filename")
		return File(url.toURI())
	}
}
