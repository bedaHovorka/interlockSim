/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit test: Main's GUI starts hand the run name to Frame (Issue #1328), headless
*/

package cz.vutbr.fit.interlockSim

import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `Main`'s two GUI starts hand [Frame.startSimulation] the name the run JSON records as `example`
 * (Issue #1328): `exampleGui` the example name, `simgui` the XML file's
 * [DispatcherRunSummaries.fileRunName], and `simgui` without a file no name.
 *
 * Headless on purpose: [Frame] is a relaxed mock bound over the Koin `single<Frame>`, so these run
 * on CI, where `GuiRunNamePersistenceTest` (which writes the real run JSON) is skipped for want
 * of a display. The simulation itself never starts, because the mock frame does nothing.
 */
@DisplayName("Main hands the GUI run name to Frame (#1328)")
class MainGuiRunNameTest : KoinTestBase() {
	private companion object {
		const val WAIT_MS = 5_000L

		/** The fixture's file name: what a `simgui` run from that file must be named. */
		const val XML_FILE_NAME = "vyhybna.xml"

		/** Below the working directory, as `Main.createContext` requires. */
		val FIXTURE_COPY_DIR = File("build/tmp/mainGuiRunNameTest")
	}

	private val frame = mockk<Frame>(relaxed = true)
	private val context = mockk<SimulationContext>(relaxed = true)

	override fun getTestModule(): Module = testModuleFull

	/** Runs after `KoinTestBase` started Koin; an override inside `includes` would not replace `Frame`. */
	@BeforeEach
	fun bindMockFrame() {
		getKoin().loadModules(listOf(module { single<Frame> { frame } }), allowOverride = true)
	}

	@AfterEach
	fun deleteFixtureCopy() {
		FIXTURE_COPY_DIR.deleteRecursively()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("exampleGui cervenyUjezd starts the frame with the example name")
	fun exampleGuiPassesExampleName() {
		val registry = mockk<ExampleRegistry>()
		every { registry.guiExamples } returns mapOf(ExampleNames.CERVENY_UJEZD to { _, _ -> context })
		every { registry.usesLlmDispatcher(any()) } returns false
		getKoin().loadModules(listOf(module { single<ExampleRegistry> { registry } }), allowOverride = true)

		getKoin().get<Main>().runExampleGui(arrayOf("exampleGui", ExampleNames.CERVENY_UJEZD, "60"))

		verify(timeout = WAIT_MS) { frame.startSimulation(ExampleNames.CERVENY_UJEZD) }
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("simgui with an XML file starts the frame with the file's base name and extension")
	fun simGuiWithFilePassesFileName() {
		overrideSimulationContextFactory()
		val xmlFile = fixtureCopyInWorkingDirectory()

		getKoin().get<Main>().loadSimWithGui(arrayOf("simgui", xmlFile.path))

		verify(timeout = WAIT_MS) { frame.startSimulation(XML_FILE_NAME) }
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("simgui without a file starts the frame with no run name")
	fun simGuiWithoutFilePassesNoName() {
		overrideSimulationContextFactory()

		getKoin().get<Main>().loadSimWithGui(arrayOf("simgui"))

		verify(timeout = WAIT_MS) { frame.startSimulation(null) }
	}

	private fun overrideSimulationContextFactory() {
		val factory = mockk<SimulationContextFactory>()
		every { factory.createContext(any<EditingContext>()) } returns context
		getKoin().loadModules(listOf(module { single<SimulationContextFactory> { factory } }), allowOverride = true)
	}

	private fun fixtureCopyInWorkingDirectory(): File {
		val file = File(FIXTURE_COPY_DIR.apply { mkdirs() }, XML_FILE_NAME)
		TestFixtures.loadShuntingXml().use { input -> file.outputStream().use { input.copyTo(it) } }
		return file
	}
}
