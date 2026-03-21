package cz.vutbr.fit.interlockSim.xml

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmlSchemaValidatorTest {

	private val validator = XmlSchemaValidator()

	@Test
	fun validMinimalXmlPassesValidation() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""
		val result = validator.validate(xml)
		assertTrue(result.isValid, "Valid XML should pass: ${result.errors}")
	}

	@Test
	fun xmlWithUnknownRootElementFailsValidation() {
		// The XSD defines <net> as the known root; an unknown root should fail
		val xml = """<?xml version="1.0"?>
			<unknown X="10" Y="10"/>"""
		val result = validator.validate(xml)
		assertFalse(result.isValid, "Unknown root element should fail validation")
		assertTrue(result.errors.isNotEmpty())
	}

	@Test
	fun xmlWithUnknownChildElementPassesPermissiveXsd() {
		// The XSD net element has no type definition, so it accepts any child content
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<UnknownElement X="1" Y="1"/>
			</net>"""
		val result = validator.validate(xml)
		assertTrue(result.isValid, "Permissive XSD net element accepts unknown children: ${result.errors}")
	}

	@Test
	fun emptyNetElementPassesXsdValidation() {
		// Note: XSD allows empty net, structural validation (InOut count) is in XmlContextReader
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10"/>"""
		val result = validator.validate(xml)
		assertTrue(result.isValid, "Empty net should pass XSD: ${result.errors}")
	}

	@Test
	fun malformedXmlFailsValidation() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<unclosed>
			</net>"""
		val result = validator.validate(xml)
		assertFalse(result.isValid, "Malformed XML should fail validation")
		assertTrue(result.errors.isNotEmpty())
	}

	@Test
	fun successFactoryCreatesValidResult() {
		val result = XmlValidationResult.success()
		assertTrue(result.isValid)
		assertTrue(result.errors.isEmpty())
	}

	@Test
	fun failureFactoryCreatesInvalidResult() {
		val result = XmlValidationResult.failure(listOf("error1", "error2"))
		assertFalse(result.isValid)
		assertTrue(result.errors.size == 2)
	}
}
