package cz.vutbr.fit.interlockSim.xml

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.xml.sax.SAXException

actual class XmlSchemaValidator actual constructor() {
	private val validator = run {
		val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
		val schema = schemaFactory.newSchema(StreamSource(StringReader(XmlSchemaContent.SCHEMA_XSD)))
		schema.newValidator()
	}

	actual fun validate(xmlContent: String): XmlValidationResult = try {
		validator.validate(StreamSource(StringReader(xmlContent)))
		XmlValidationResult.success()
	} catch (e: SAXException) {
		XmlValidationResult.failure(listOf(e.message ?: "Validation failed"))
	}
}
