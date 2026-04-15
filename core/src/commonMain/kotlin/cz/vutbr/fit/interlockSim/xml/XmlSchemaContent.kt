package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.util.Resources

/**
 * Access to the XSD schema for railway network XML validation.
 *
 * Single source of truth is the resource file at
 * `core/src/commonMain/resources/cz/vutbr/fit/interlockSim/resource/data.xsd`.
 * Loaded via multiplatform [Resources.read] so both JVM (classpath) and
 * linuxX64 (filesystem) see identical content — no inline XSD, no drift test.
 */
object XmlSchemaContent {
	val SCHEMA_XSD: String by lazy {
		Resources.read("cz/vutbr/fit/interlockSim/resource/data.xsd")
	}
}
