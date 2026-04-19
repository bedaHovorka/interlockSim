package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell

/**
 * Factory registry replacing Java reflection for XML element construction.
 * Maps XML tag names to domain object constructors and vice versa.
 */
object NodeCellFactory {
	/**
	 * Returns the XML tag name for a given [NodeCell] instance.
	 *
	 * Only handles InOut, RailSemaphore, and RailSwitch — these are the NodeCell
	 * subclasses serialized as individual XML elements. SimpleTrackBlock and the
	 * root "net" element are handled separately (track blocks are written by
	 * [XmlContextWriter.writeEdges], "net" is the document root).
	 */
	fun tagNameFor(cell: Cell): String =
		when (cell) {
			is InOut -> "InOut"
			is RailSemaphore -> "RailSemaphore"
			is RailSwitch -> "RailSwitch"
			else -> error("Unknown cell type: ${cell::class}")
		}

	/** Checks if a tag name corresponds to a known element. */
	fun isKnownTag(tagName: String): Boolean = tagName in knownTags

	private val knownTags =
		setOf(
			"InOut",
			"RailSemaphore",
			"RailSwitch",
			"SimpleTrackBlock",
			"net"
		)
}
