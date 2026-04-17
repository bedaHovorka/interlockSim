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
		val result = validator.validate(InlineXmlSnippets.VALID_MINIMAL_ONE_INOUT)
		assertThat(result.isValid).isTrue()
	}

	@Test
	fun xmlWithUnknownRootElementFailsValidation() {
		val result = validator.validate(InlineXmlSnippets.UNKNOWN_ROOT_ELEMENT)
		assertThat(result.isValid).isFalse()
		assertThat(result.errors).isNotEmpty()
	}

	@Test
	fun xmlWithUnknownChildElementPassesPermissiveXsd() {
		// The XSD net element has no type definition, so it accepts any child content
		val result = validator.validate(InlineXmlSnippets.NET_WITH_UNKNOWN_CHILD)
		assertThat(result.isValid).isTrue()
	}

	@Test
	fun emptyNetElementPassesXsdValidation() {
		// Note: XSD allows empty net, structural validation (InOut count) is in XmlContextReader
		val result = validator.validate(InlineXmlSnippets.EMPTY_NET_SELF_CLOSED)
		assertThat(result.isValid).isTrue()
	}

	@Test
	fun malformedXmlFailsValidation() {
		val result = validator.validate(InlineXmlSnippets.MALFORMED_UNCLOSED_TAG)
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
