package cz.vutbr.fit.interlockSim.xml

data class XmlValidationResult(
	val isValid: Boolean,
	val errors: List<String> = emptyList()
) {
	companion object {
		fun success(): XmlValidationResult = XmlValidationResult(isValid = true)

		fun failure(errors: List<String>): XmlValidationResult = XmlValidationResult(isValid = false, errors = errors)
	}
}
