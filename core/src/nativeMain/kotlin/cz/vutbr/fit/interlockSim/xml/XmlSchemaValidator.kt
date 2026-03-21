@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cz.vutbr.fit.interlockSim.xml

import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import libxml2.XML_PARSE_NONET
import libxml2.xmlFreeDoc
import libxml2.xmlGetLastError
import libxml2.xmlReadMemory
import libxml2.xmlResetLastError
import libxml2.xmlSchemaFree
import libxml2.xmlSchemaFreeParserCtxt
import libxml2.xmlSchemaFreeValidCtxt
import libxml2.xmlSchemaNewDocParserCtxt
import libxml2.xmlSchemaNewValidCtxt
import libxml2.xmlSchemaParse
import libxml2.xmlSchemaValidateDoc

actual class XmlSchemaValidator actual constructor() {

	// Unlike the JVM actual (which pre-compiles the Schema once at construction),
	// the native actual re-parses XmlSchemaContent.SCHEMA_XSD on every call.
	// libxml2's xmlSchemaPtr is not safely reusable across calls in K/N, so this
	// trade-off is intentional. For this project's validation volume it is acceptable.
	actual fun validate(xmlContent: String): XmlValidationResult {
		xmlResetLastError()

		// Encode once — cinterop would encode again if we passed the String directly.
		// XML_PARSE_NONET blocks network access to prevent XXE via external entity URIs.
		// Note: XML_PARSE_NOENT would *expand* entities (wrong direction for XXE prevention).
		val xmlBytes = xmlContent.encodeToByteArray()
		val doc = xmlReadMemory(xmlContent, xmlBytes.size, null, null, XML_PARSE_NONET.toInt())
			?: return XmlValidationResult.failure(
				listOf(
					extractLastError()
						?: "Failed to parse XML document"
				)
			)

		return try {
			validateWithSchema(doc)
		} finally {
			xmlFreeDoc(doc)
		}
	}

	private fun validateWithSchema(
		doc: libxml2.xmlDocPtr
	): XmlValidationResult {
		// Parse XSD as an XML document, then create schema from doc.
		// This avoids the dangling-pointer issue with
		// xmlSchemaNewMemParserCtxt (K/N String auto-conversion
		// creates a temporary that may be freed before
		// xmlSchemaParse reads it).
		val xsdContent = XmlSchemaContent.SCHEMA_XSD
		val xsdBytes = xsdContent.encodeToByteArray()
		val xsdDoc = xmlReadMemory(xsdContent, xsdBytes.size, null, null, XML_PARSE_NONET.toInt())
			?: return XmlValidationResult.failure(
				listOf(
					extractLastError()
						?: "Failed to parse XSD as XML document"
				)
			)

		// Note: xmlSchemaNewDocParserCtxt takes ownership of the
		// xsdDoc — do NOT call xmlFreeDoc on it separately.
		val parserCtxt = xmlSchemaNewDocParserCtxt(xsdDoc)
		if (parserCtxt == null) {
			xmlFreeDoc(xsdDoc)
			return XmlValidationResult.failure(
				listOf(
					extractLastError()
						?: "Failed to create schema parser context"
				)
			)
		}

		val schema = try {
			xmlSchemaParse(parserCtxt)
		} finally {
			xmlSchemaFreeParserCtxt(parserCtxt)
		} ?: return XmlValidationResult.failure(
			listOf(extractLastError() ?: "Failed to parse XSD schema")
		)

		val validCtxt = xmlSchemaNewValidCtxt(schema)
		if (validCtxt == null) {
			xmlSchemaFree(schema)
			return XmlValidationResult.failure(
				listOf("Failed to create validation context")
			)
		}

		return try {
			xmlResetLastError()
			val result = xmlSchemaValidateDoc(validCtxt, doc)
			if (result == 0) {
				XmlValidationResult.success()
			} else {
				val errorMsg = extractLastError()
					?: "Schema validation failed (code: $result)"
				XmlValidationResult.failure(listOf(errorMsg))
			}
		} finally {
			xmlSchemaFreeValidCtxt(validCtxt)
			xmlSchemaFree(schema)
		}
	}

	private fun extractLastError(): String? {
		val error = xmlGetLastError() ?: return null
		return error.pointed.message?.toKString()?.trim()
	}
}
