/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.objects.cells.InOut

/**
 * Structure check for the `rudyUjezd.xml` fixture, shared by the parse test in
 * `XMLContextFactoryParseTest` and the stream round trip in `XMLContextFactoryOutputStreamTest`
 * (Issue #1035 review round).
 *
 * Asserts that the four station InOuts of the fixture exist in [context] and returns them
 * in the order first end (f1, f2), second end (s1, s2):
 *
 * - f1: `<InOut X="37" Y="32" SpatialType="HORIZONTAL" orientation="true" name="" />`
 * - f2: `<InOut X="37" Y="31" SpatialType="HORIZONTAL" orientation="true" name="" />`
 * - s1: `<InOut X="5" Y="31" SpatialType="HORIZONTAL" orientation="false" name="" />`
 * - s2: `<InOut X="5" Y="32" SpatialType="HORIZONTAL" orientation="false" name="" />`
 */
fun assertRudyUjezdStationInOuts(context: Context<*, *>): List<InOut> {
	val grid = context.getRailWayNetGrid()
	val f1 = grid.getCellAt(37, 32)
	val f2 = grid.getCellAt(37, 31)
	val s1 = grid.getCellAt(5, 31)
	val s2 = grid.getCellAt(5, 32)

	assertThat(f1).isNotNull().isInstanceOf(InOut::class)
	assertThat(f2).isNotNull().isInstanceOf(InOut::class)
	assertThat(s1).isNotNull().isInstanceOf(InOut::class)
	assertThat(s2).isNotNull().isInstanceOf(InOut::class)

	return listOf(f1 as InOut, f2 as InOut, s1 as InOut, s2 as InOut)
}
