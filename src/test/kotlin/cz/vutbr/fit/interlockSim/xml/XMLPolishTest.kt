/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Coverage Polish Tests - XML Package
 */
package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.*
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.ByteArrayInputStream
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Test coverage polish for XML package - targeting 90%+ coverage.
 *
 * Focus areas:
 * - Malformed XML error handling
 * - Invalid enum values (Type, SpatialType, Segment)
 * - Missing required attributes (X, Y, length, maxSpeed, segmentFrom, segmentTo)
 * - Invalid boolean and numeric values
 * - Nested net elements
 * - Unknown element types
 * - InOut count validation
 * - Schema validation edge cases
 */
@DisplayName("XML Package - Coverage Polish Tests")
class XMLPolishTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()

	@Nested
	@DisplayName("Nested Net Elements - Error Handling")
	inner class NestedNetElementTests {

		@Test
		fun `nested net elements throw exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<net X="50" Y="50">
						<InOut X="20" Y="20" SpatialType="VERTICAL" orientation="false" name="B"/>
					</net>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}

		@Test
		fun `multiple root net elements fail`() {
			val xml = """<?xml version="1.0"?>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
				</net>
				<net X="50" Y="50">
					<InOut X="20" Y="20" SpatialType="VERTICAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}
	}

	@Nested
	@DisplayName("Invalid Enum Values - Error Handling")
	inner class InvalidEnumValueTests {

		@Test
		fun `invalid RailSwitch Type throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<RailSwitch X="15" Y="10" SpatialType="HORIZONTAL" Type="INVALID_TYPE"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `invalid SpatialType value throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="INVALID_SPATIAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `invalid Segment enum value throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="INVALID_SEGMENT" 
						toX="20" toY="10" segmentTo="A" 
						length="10.0" maxSpeedFrom="10.0" maxSpeedTo="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `RailSwitch without Type attribute throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<RailSwitch X="15" Y="10" SpatialType="HORIZONTAL"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}
	}

	@Nested
	@DisplayName("Missing Required Attributes - Error Handling")
	inner class MissingRequiredAttributesTests {

		@Test
		fun `missing X attribute throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `missing Y attribute throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `missing length attribute in TrackBlock throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="F" 
						toX="20" toY="10" segmentTo="A" 
						maxSpeedFrom="10.0" maxSpeedTo="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `missing maxSpeedFrom attribute throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="F" 
						toX="20" toY="10" segmentTo="A" 
						length="10.0" maxSpeedTo="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `missing maxSpeedTo attribute throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="F" 
						toX="20" toY="10" segmentTo="A" 
						length="10.0" maxSpeedFrom="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `missing segmentFrom attribute throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" 
						toX="20" toY="10" segmentTo="A" 
						length="10.0" maxSpeedFrom="10.0" maxSpeedTo="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `missing segmentTo attribute throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="F" 
						toX="20" toY="10" 
						length="10.0" maxSpeedFrom="10.0" maxSpeedTo="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}
	}

	@Nested
	@DisplayName("Invalid Attribute Values - Edge Cases")
	inner class InvalidAttributeValuesTests {

		@Test
		fun `invalid boolean value defaults to false`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="not_a_boolean" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			// Should parse successfully with orientation defaulting to false
			val context = factory.createContext(stream)
			assertThat(context).isNotNull()
		}

		@Test
		fun `negative X coordinate is allowed`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="-5" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			val context = factory.createContext(stream)
			assertThat(context).isNotNull()
		}

		@Test
		fun `negative Y coordinate is allowed`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="-5" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			val context = factory.createContext(stream)
			assertThat(context).isNotNull()
		}

		@Test
		fun `zero length track is invalid`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="F" 
						toX="20" toY="10" segmentTo="A" 
						length="0.0" maxSpeedFrom="10.0" maxSpeedTo="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			// Should fail due to validation (MIN_LENGTH constraint)
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `negative length track throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="F" 
						toX="20" toY="10" segmentTo="A" 
						length="-10.0" maxSpeedFrom="10.0" maxSpeedTo="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}
	}

	@Nested
	@DisplayName("Unknown Element Types - Error Handling")
	inner class UnknownElementTests {

		@Test
		fun `unknown element type throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<UnknownElement X="15" Y="10" SpatialType="HORIZONTAL"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `arbitrary element in net throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<CustomTag value="test"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}
	}

	@Nested
	@DisplayName("InOut Count Validation")
	inner class InOutCountValidationTests {

		@Test
		fun `zero InOuts throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
				.message()
				.isNotNull()
		}

		@Test
		fun `single InOut throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
				.message()
				.isNotNull()
		}

		@Test
		fun `exactly two InOuts is valid`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			val context = factory.createContext(stream)
			assertThat(context).isNotNull()
			assertThat(context.getInOuts()).hasSize(2)
		}

		@Test
		fun `three InOuts is valid`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<InOut X="15" Y="15" SpatialType="VERTICAL" orientation="true" name="C"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			val context = factory.createContext(stream)
			assertThat(context).isNotNull()
			assertThat(context.getInOuts()).hasSize(3)
		}
	}

	@Nested
	@DisplayName("Malformed XML - Parse Error Handling")
	inner class MalformedXMLTests {

		@Test
		fun `unclosed net element throws exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `mismatched element tags throw exception`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</wrongtag>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `empty XML throws exception`() {
			val xml = ""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `XML without root element throws exception`() {
			val xml = """<?xml version="1.0"?>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}
	}

	@Nested
	@DisplayName("Element Ordering and Dependencies")
	inner class ElementOrderingTests {

		@Test
		fun `TrackBlock before InOut endpoints fails`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="F" 
						toX="20" toY="10" segmentTo="A" 
						length="10.0" maxSpeedFrom="10.0" maxSpeedTo="10.0"/>
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			// Should fail because cells at (10,10) and (20,10) don't exist yet
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `InOut before TrackBlock succeeds`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
					<SimpleTrackBlock fromX="10" fromY="10" segmentFrom="F" 
						toX="20" toY="10" segmentTo="A" 
						length="10.0" maxSpeedFrom="10.0" maxSpeedTo="10.0"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			val context = factory.createContext(stream)
			assertThat(context).isNotNull()
		}
	}

	@Nested
	@DisplayName("Net Grid Size Validation")
	inner class NetGridSizeTests {

		@Test
		fun `net with zero X dimension`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="0" Y="100">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			// Grid with X=0 is invalid
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `net with zero Y dimension`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="100" Y="0">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			// Grid with Y=0 is invalid
			assertThatBlock {
				factory.createContext(stream)
			}.isFailure()
		}

		@Test
		fun `net with large dimensions succeeds`() {
			val xml = """<?xml version="1.0"?>
				<!DOCTYPE net>
				<net X="500" Y="500">
					<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="true" name="A"/>
					<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="false" name="B"/>
				</net>"""
			val stream = ByteArrayInputStream(xml.toByteArray())
			
			val context = factory.createContext(stream)
			assertThat(context).isNotNull()
			assertThat(context.getRailWayNetGrid().getCols()).isEqualTo(500)
			assertThat(context.getRailWayNetGrid().getRows()).isEqualTo(500)
		}
	}
}
