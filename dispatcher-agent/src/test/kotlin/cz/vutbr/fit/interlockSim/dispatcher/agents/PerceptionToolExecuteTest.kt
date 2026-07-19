/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameAs
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.AllBlockOccupanciesTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.AllSignalAspectsTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.AllTrainPositionsTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.AllTrainTimetablesTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.BlockOccupancyTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.SignalAspectTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.TrainPerceptionTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.TrainPositionTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.TrainTimetableTool
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SemaphoreReading
import cz.vutbr.fit.interlockSim.ports.TimetableReading
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies the 9 perception tools forward arguments to [NetworkPerceptionPort] correctly and
 * wrap the port result in [ToolResult.Success] (SP1.6, Issue #551 review #3; SP2a.1 #552).
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); SP2a.1 (#552) adds TrainPerceptionTool
 */
class PerceptionToolExecuteTest {
	private val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)

	@BeforeEach
	fun setUp() {
		// relaxed=true gives sane defaults; each test overrides the call it verifies.
		every { perceptionPort.signalAspect(any()) } returns null
	}

	@Test
	fun `signal_aspect forwards semaphoreName and wraps the reading`() {
		val reading = mockk<SemaphoreReading>()
		every { perceptionPort.signalAspect("zA") } returns reading

		val result = runBlocking { SignalAspectTool(perceptionPort).execute(mapOf("semaphoreName" to "zA")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(reading)
		verify(exactly = 1) { perceptionPort.signalAspect("zA") }
	}

	@Test
	fun `signal_aspect wraps a null reading when the semaphore is unknown`() {
		every { perceptionPort.signalAspect("nope") } returns null

		val result = runBlocking { SignalAspectTool(perceptionPort).execute(mapOf("semaphoreName" to "nope")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isNull()
	}

	@Test
	fun `block_occupancy forwards blockId and wraps the reading`() {
		val reading = mockk<BlockOccupancyReading>()
		every { perceptionPort.blockOccupancy("k1") } returns reading

		val result = runBlocking { BlockOccupancyTool(perceptionPort).execute(mapOf("blockId" to "k1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(reading)
		verify(exactly = 1) { perceptionPort.blockOccupancy("k1") }
	}

	@Test
	fun `train_position forwards trainId and wraps the reading`() {
		val reading = mockk<TrainPositionReading>()
		every { perceptionPort.trainPosition("Train #1") } returns reading

		val result = runBlocking { TrainPositionTool(perceptionPort).execute(mapOf("trainId" to "Train #1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(reading)
		verify(exactly = 1) { perceptionPort.trainPosition("Train #1") }
	}

	@Test
	fun `train_timetable forwards trainId and wraps the reading`() {
		val reading = mockk<TimetableReading>()
		every { perceptionPort.trainTimetable("Train #2") } returns reading

		val result = runBlocking { TrainTimetableTool(perceptionPort).execute(mapOf("trainId" to "Train #2")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(reading)
		verify(exactly = 1) { perceptionPort.trainTimetable("Train #2") }
	}

	@Test
	fun `all_signal_aspects wraps the list and ignores args`() {
		val list = listOf(mockk<SemaphoreReading>())
		every { perceptionPort.allSignalAspects() } returns list

		val result = runBlocking { AllSignalAspectsTool(perceptionPort).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(list)
		verify(exactly = 1) { perceptionPort.allSignalAspects() }
	}

	@Test
	fun `all_block_occupancies wraps the list`() {
		val list = listOf(mockk<BlockOccupancyReading>())
		every { perceptionPort.allBlockOccupancies() } returns list

		val result = runBlocking { AllBlockOccupanciesTool(perceptionPort).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(list)
		verify(exactly = 1) { perceptionPort.allBlockOccupancies() }
	}

	@Test
	fun `all_train_positions wraps the list`() {
		val list = listOf(mockk<TrainPositionReading>())
		every { perceptionPort.allTrainPositions() } returns list

		val result = runBlocking { AllTrainPositionsTool(perceptionPort).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(list)
		verify(exactly = 1) { perceptionPort.allTrainPositions() }
	}

	@Test
	fun `all_train_timetables wraps the list`() {
		val list = listOf(mockk<TimetableReading>())
		every { perceptionPort.allTrainTimetables() } returns list

		val result = runBlocking { AllTrainTimetablesTool(perceptionPort).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(list)
		assertThat(list).hasSize(1)
		assertThat((result as ToolResult.Success).data).isNotNull()
		verify(exactly = 1) { perceptionPort.allTrainTimetables() }
	}

	@Test
	fun `perception tool translates a port exception into ToolResult Error`() {
		every { perceptionPort.signalAspect("boom") } throws IllegalArgumentException("nope")

		val result = runBlocking { SignalAspectTool(perceptionPort).execute(mapOf("semaphoreName" to "boom")) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat((result as ToolResult.Error).cause).isNotNull()
	}

	@Test
	fun `train_perception forwards trainId and wraps the reading`() {
		val reading = mockk<TrainPerceptionReading>()
		every { perceptionPort.trainPerception("Train #1") } returns reading

		val result = runBlocking { TrainPerceptionTool(perceptionPort).execute(mapOf("trainId" to "Train #1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(reading)
		verify(exactly = 1) { perceptionPort.trainPerception("Train #1") }
	}

	@Test
	fun `train_perception wraps null when trainId is unknown`() {
		every { perceptionPort.trainPerception("ghost") } returns null

		val result = runBlocking { TrainPerceptionTool(perceptionPort).execute(mapOf("trainId" to "ghost")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isNull()
	}

	@Test
	fun `train_perception returns Error when trainId param is missing`() {
		val result = runBlocking { TrainPerceptionTool(perceptionPort).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
	}
}
