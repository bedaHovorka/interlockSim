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
 * root element, empty net, specific attribute combinations, small per-test
 * permutations). All substantial XML lives in
 * `core-test/src/commonMain/resources/cz/vutbr/fit/interlockSim/xml/fixtures/`.
 */
@Suppress("LargeClass")
internal object InlineXmlSnippets {
	// --- Schema validator snippets ---

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

	/**
	 * Valid `<net>` per XSD schema (permissive XSD accepts unknown children).
	 * Used by [cz.vutbr.fit.interlockSim.xml.XmlSchemaValidatorTest] only —
	 * XmlContextReader now throws on unknown elements, so this snippet must NOT
	 * be used in reader tests (use [NET_WITH_UNKNOWN_CHILD_INOUT] instead).
	 */
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

	// --- Reader: happy-path snippets ---

	/** Two InOuts (A, B) connected by a SimpleTrackBlock on a 30×30 grid. */
	const val READER_TWO_INOUTS_WITH_TRACK: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
				<SimpleTrackBlock fromX="1" fromY="1" toX="5" toY="5"
					fromSegment="F" toSegment="A"
					length="100.0" maxSpeedfrom="80.0" maxSpeedto="80.0"/>
			</net>"""

	/** Network with an unnamed SIMPLE_LEFT_FALSE switch between InOut A and B. */
	const val READER_WITH_UNNAMED_SWITCH: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="3" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_FALSE"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""

	/** Network with an unnamed vertical RailSemaphore between InOut A and B. */
	const val READER_WITH_UNNAMED_SEMAPHORE: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="VERTICAL" orientation="true"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""

	/** Network with a named horizontal RailSemaphore "S1". */
	const val READER_WITH_NAMED_SEMAPHORE: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="HORIZONTAL" orientation="true" name="S1"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""

	/** Network with a named SIMPLE_LEFT_FALSE RailSwitch "W1". */
	const val READER_WITH_NAMED_SWITCH: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="3" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_FALSE" name="W1"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""

	/** Network with exactly one InOut on a 10×10 grid (valid with bidirectional operation). */
	const val READER_SINGLE_INOUT: String = VALID_MINIMAL_ONE_INOUT

	/** 15×20 grid with one InOut — exercises non-square grid dimensions. */
	const val READER_NON_SQUARE_GRID: String =
		"""<?xml version="1.0"?>
			<net X="15" Y="20">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""

	/** Two InOuts A, B — exercises InOut orientation parsing. */
	const val READER_TWO_INOUTS: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""

	/** Network exercising all four SpatialType values (HORIZONTAL, VERTICAL, DIAGONAL1, DIAGONAL2). */
	const val READER_ALL_SPATIAL_TYPES: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="VERTICAL" orientation="true"/>
				<RailSemaphore X="5" Y="5" SpatialType="DIAGONAL1" orientation="false"/>
				<RailSemaphore X="7" Y="7" SpatialType="DIAGONAL2" orientation="true"/>
			</net>"""

	// --- Reader: validation/error snippets ---

	/** Empty `<net>` (opened/closed, not self-closed). Zero InOuts — fails structural validation. */
	const val EMPTY_NET_OPEN_CLOSE: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10">
			</net>"""

	/** XML with `<other>` root instead of `<net>`. */
	const val ROOT_IS_OTHER: String =
		"""<?xml version="1.0"?>
			<other X="10" Y="10"/>"""

	/** `<net>` missing the Y attribute. */
	const val NET_MISSING_Y_ATTR: String =
		"""<?xml version="1.0"?>
			<net X="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""

	/** InOut with SpatialType="INVALID". */
	const val INOUT_INVALID_SPATIAL_TYPE: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="INVALID" orientation="false" name="A"/>
			</net>"""

	/** SimpleTrackBlock where from == to. */
	const val TRACK_FROM_EQUALS_TO: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<SimpleTrackBlock fromX="1" fromY="1" toX="1" toY="1"
					fromSegment="F" toSegment="A"
					length="100.0" maxSpeedfrom="80.0" maxSpeedto="80.0"/>
			</net>"""

	/** SimpleTrackBlock pointing at a cell that is not defined. */
	const val TRACK_REFERENCES_MISSING_CELL: String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<SimpleTrackBlock fromX="1" fromY="1" toX="9" toY="9"
					fromSegment="F" toSegment="A"
					length="100.0" maxSpeedfrom="80.0" maxSpeedto="80.0"/>
			</net>"""

	/** Network with an unknown element — reader must throw on this. */
	const val NET_WITH_UNKNOWN_CHILD_INOUT: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<SomeUnknownElement foo="bar"/>
			</net>"""

	/** Two InOuts + a SimpleTrackBlock for testing file-based parseFile(). 20×20, length 50m. */
	const val READER_PARSE_FILE_NETWORK: String =
		"""<?xml version="1.0"?>
			<net X="20" Y="20">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
				<SimpleTrackBlock fromX="1" fromY="1" toX="5" toY="5"
					fromSegment="F" toSegment="A"
					length="50.0" maxSpeedfrom="60.0" maxSpeedto="60.0"/>
			</net>"""

	/** Net with currentMaxSpeed="120.0". */
	const val NET_CURRENT_MAX_SPEED: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10" currentMaxSpeed="120.0">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""

	/** Net with currentTrackLength="400.0". */
	const val NET_CURRENT_TRACK_LENGTH: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10" currentTrackLength="400.0">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""

	/** Net with currentNameString="My Network". */
	const val NET_CURRENT_NAME_STRING: String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10" currentNameString="My Network">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""

	/** Net with all three currentXxx attributes. */
	const val NET_ALL_CURRENT_ATTRS: String =
		"""<?xml version="1.0"?>
			<net X="15" Y="15" currentMaxSpeed="90.5" currentTrackLength="250.0" currentNameString="Full Test">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""

	// --- Parameterized helpers (content depends on test inputs) ---

	/** Net with a RailSemaphore whose name is supplied (used for name-length tests). */
	fun readerNetWithSemaphoreName(name: String): String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="HORIZONTAL" orientation="true" name="$name"/>
			</net>"""

	/** Net with a RailSwitch whose name is supplied (used for invalid-name tests). */
	fun readerNetWithSwitchName(name: String): String =
		"""<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="3" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_FALSE" name="$name"/>
			</net>"""

	/** Net with a RailSwitch of the given type at row `y` (used for all-switch-types test). */
	fun readerNetWithSwitchType(
		type: String,
		y: Int
	): String =
		"""<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="$y" SpatialType="HORIZONTAL" Type="$type"/>
			</net>"""
}
