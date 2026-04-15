/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.xml

/**
 * The sole permitted location for inline XML strings in Kotlin source under :core.
 *
 * Every entry is a tiny parser-edge-case snippet where extracting to an
 * external fixture would obscure the test's intent (malformed XML, unknown
 * root element, empty net, minimal attribute combinations). All substantial
 * XML lives in
 * `core-test/src/commonMain/resources/cz/vutbr/fit/interlockSim/xml/fixtures/`.
 */
internal object InlineXmlSnippets {
	/** Minimal valid XML with one InOut, 10×10 grid. */
	const val VALID_MINIMAL_ONE_INOUT: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""

	/** XML whose root element is not `<net>` (should fail schema validation). */
	const val UNKNOWN_ROOT_ELEMENT: String =
		"""<?xml version="1.0"?>
			<unknown X="10" Y="10"/>"""

	/** Valid `<net>` containing an element the XSD does not define (XSD is permissive). */
	const val NET_WITH_UNKNOWN_CHILD: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10">
				<UnknownElement X="1" Y="1"/>
			</net>"""

	/** Empty self-closed `<net>` element (valid XSD, fails structural validation). */
	const val EMPTY_NET_SELF_CLOSED: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10"/>"""

	/** Malformed XML — unclosed `<unclosed>` tag inside `<net>`. */
	const val MALFORMED_UNCLOSED_TAG: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10">
				<unclosed>
			</net>"""
}
