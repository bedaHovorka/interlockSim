package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import kotlin.test.Test

class XmlSchemaValidatorTest {

	private val validator = XmlSchemaValidator()

	@Test
	fun validMinimalXmlPassesValidation() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""
		val result = validator.validate(xml)
		assertThat(result.isValid).isTrue()
	}

	@Test
	fun xmlWithUnknownRootElementFailsValidation() {
		val xml = """<?xml version="1.0"?>
			<unknown X="10" Y="10"/>"""
		val result = validator.validate(xml)
		assertThat(result.isValid).isFalse()
		assertThat(result.errors).isNotEmpty()
	}

	@Test
	fun xmlWithUnknownChildElementPassesPermissiveXsd() {
		// The XSD net element has no type definition, so it accepts any child content
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<UnknownElement X="1" Y="1"/>
			</net>"""
		val result = validator.validate(xml)
		assertThat(result.isValid).isTrue()
	}

	@Test
	fun emptyNetElementPassesXsdValidation() {
		// Note: XSD allows empty net, structural validation (InOut count) is in XmlContextReader
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10"/>"""
		val result = validator.validate(xml)
		assertThat(result.isValid).isTrue()
	}

	@Test
	fun malformedXmlFailsValidation() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<unclosed>
			</net>"""
		val result = validator.validate(xml)
		assertThat(result.isValid).isFalse()
		assertThat(result.errors).isNotEmpty()
	}

	@Test
	fun successFactoryCreatesValidResult() {
		val result = XmlValidationResult.success()
		assertThat(result.isValid).isTrue()
		assertThat(result.errors).isEmpty()
	}

	@Test
	fun failureFactoryCreatesInvalidResult() {
		val result = XmlValidationResult.failure(listOf("error1", "error2"))
		assertThat(result.isValid).isFalse()
		assertThat(result.errors).hasSize(2)
	}
}
