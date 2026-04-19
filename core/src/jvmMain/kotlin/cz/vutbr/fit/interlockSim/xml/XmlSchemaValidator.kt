package cz.vutbr.fit.interlockSim.xml

import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

actual class XmlSchemaValidator actual constructor() {
	private val schema =
		run {
			val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
			schemaFactory.newSchema(StreamSource(StringReader(XmlSchemaContent.SCHEMA_XSD)))
		}

	actual fun validate(xmlContent: String): XmlValidationResult =
		try {
			schema.newValidator().validate(StreamSource(StringReader(xmlContent)))
			XmlValidationResult.success()
		} catch (e: SAXException) {
			XmlValidationResult.failure(listOf(e.message ?: "Validation failed"))
		} catch (e: IOException) {
			XmlValidationResult.failure(listOf(e.message ?: "I/O error during validation"))
		}
}
