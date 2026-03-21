package cz.vutbr.fit.interlockSim.xml

expect class XmlSchemaValidator() {
	fun validate(xmlContent: String): XmlValidationResult
}
