/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.GridTransformer
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import kotlin.test.Test

/**
 * Tests for the shared [resolveEndpoint] lookup used by the interlocking facade and the actuator port.
 */
class EndpointResolutionTest {
	private val inOut: DynamicInOut =
		GridTransformer.createDynamic(InOut("A", true, Cell.SpatialType.HORIZONTAL))
	private val semaphore: DynamicRailSemaphore =
		createDynamicInstance(RailSemaphore("S1", true, Cell.SpatialType.HORIZONTAL))

	@Test
	fun resolvesInOutByName() {
		val result = resolveEndpoint("A", mapOf("A" to inOut), mapOf("S1" to semaphore))
		assertThat(result).isSameInstanceAs(inOut)
	}

	@Test
	fun resolvesSemaphoreByName() {
		val result = resolveEndpoint("S1", mapOf("A" to inOut), mapOf("S1" to semaphore))
		assertThat(result).isSameInstanceAs(semaphore)
	}

	@Test
	fun inOutWinsOverSemaphoreOfTheSameName() {
		val result = resolveEndpoint("X", mapOf("X" to inOut), mapOf("X" to semaphore))
		assertThat(result).isSameInstanceAs(inOut)
	}

	@Test
	fun unknownNameResolvesToNull() {
		val result = resolveEndpoint("missing", mapOf("A" to inOut), mapOf("S1" to semaphore))
		assertThat(result).isNull()
	}
}
