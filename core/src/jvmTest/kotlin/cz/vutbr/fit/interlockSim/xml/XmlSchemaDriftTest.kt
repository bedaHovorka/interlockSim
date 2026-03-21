package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test

/**
 * Verifies that the embedded [XmlSchemaContent.SCHEMA_XSD] constant stays in sync
 * with the file-system copy at `data.xsd` in JVM resources.
 *
 * If this test fails, someone updated the resource file without updating the
 * embedded constant (or vice versa).
 */
class XmlSchemaDriftTest {

	@Test
	fun `embedded XSD matches resource file`() {
		val resourceXsd = javaClass.getResourceAsStream(
			"/cz/vutbr/fit/interlockSim/resource/data.xsd"
		)
		assertThat(resourceXsd).isNotNull()

		val fileContent = resourceXsd!!.bufferedReader().use { it.readText() }
		// Normalize trailing whitespace per line — the resource file may have
		// trailing spaces on blank comment lines that the embedded constant does not.
		assertThat(normalizeWhitespace(XmlSchemaContent.SCHEMA_XSD))
			.isEqualTo(normalizeWhitespace(fileContent))
	}

	private fun normalizeWhitespace(text: String): String =
		text.lines().joinToString("\n") { it.trimEnd() }.trim()
}
