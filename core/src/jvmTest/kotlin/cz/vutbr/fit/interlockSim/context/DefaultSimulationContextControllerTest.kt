package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.hovorka.kdisco.Simulation
import org.junit.jupiter.api.Test

class DefaultSimulationContextControllerTest {
	@Test
	fun testKdiscoApi() {
		val sim = Simulation.create {}
		// Let's see if we can compile and run this
		assertThat(sim.time()).isEqualTo(0.0)

		@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
		val eventQueue = sim.context.eventQueue
		val nextEventTime = eventQueue.peek()?.time ?: Double.MAX_VALUE
		assertThat(nextEventTime).isEqualTo(Double.MAX_VALUE)
	}
}
