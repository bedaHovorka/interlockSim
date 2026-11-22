/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Integration test: a GUI run persists the example or XML file it ran (Issue #1328)
	Tests require a non-headless display — skipped automatically in CI.
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.ExampleNames
import cz.vutbr.fit.interlockSim.ExampleRegistry
import cz.vutbr.fit.interlockSim.Main
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultDispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunSnapshot
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.createMockShuntingContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.module
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * A GUI run records in its run JSON which example or XML file it ran (Issue #1328).
 *
 * Before #1328 every GUI run persisted `example = null`, so the sweep report's Performance table
 * showed it as `(unnamed)`. The name now travels from each of the three GUI starts to
 * [Frame.startSimulation] and on to `DispatcherRunSummaries.finishAndPersist`:
 * - `Main.runExampleGui` passes the example name,
 * - `Main.loadSimWithGui` passes the XML file's base name with its extension,
 * - `MenuBar`'s Start action passes the same, captured before it clears the current file.
 *
 * Each test runs a [MockSimulationContext] (its `run()` returns at once, so the run ends on its
 * own) with a real [DefaultDispatcherRunRecorder] and a store under a temporary directory. A
 * `DispatcherRunConfig.runsRoot` makes `finishAndPersist` write the file although no dispatcher
 * agent is wired, the same rule a sweep child relies on.
 */
@DisplayName("A GUI run persists the example or XML file it ran (#1328)")
class GuiRunNamePersistenceTest : AbstractFrameTestBase() {
	private companion object {
		const val WAIT_MS = 5_000L
		const val POLL_MS = 20L

		/** The XML fixture's file name: what a run from that file must persist. */
		const val XML_FILE_NAME = "vyhybna.xml"

		/** Where the `simgui` test copies the fixture: below the working directory, as Main requires. */
		val FIXTURE_COPY_DIR = File("build/tmp/guiRunNamePersistenceTest")
	}

	private val contexts = mutableListOf<SimulationContext>()

	/**
	 * Stops only the frames this test registered. Resolving the Koin `Frame` here would create it
	 * even when the test was skipped as headless, and the resulting `HeadlessException` would turn
	 * the skip into a failure.
	 */
	@AfterEach
	fun closeContexts() {
		frames.forEach { frame -> SwingUtilities.invokeAndWait { frame.stopSimulation() } }
		contexts.forEach { it.close() }
		FIXTURE_COPY_DIR.deleteRecursively()
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("an exampleGui cervenyUjezd run persists example = cervenyUjezd")
	fun exampleGuiRunPersistsExampleName(
		@TempDir root: Path
	) {
		val context = persistingContext(root)
		val registry = mockk<ExampleRegistry>()
		every { registry.guiExamples } returns mapOf(ExampleNames.CERVENY_UJEZD to { _, _ -> context })
		every { registry.usesLlmDispatcher(any()) } returns false
		getKoin().loadModules(listOf(module { single<ExampleRegistry> { registry } }), allowOverride = true)
		frames.add(getKoin().get<Frame>())

		getKoin().get<Main>().runExampleGui(arrayOf("exampleGui", ExampleNames.CERVENY_UJEZD, "60"))

		assertThat(awaitSnapshot(root).example, "example").isEqualTo(ExampleNames.CERVENY_UJEZD)
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("a simgui run from an XML file persists the file's base name with its extension")
	fun simGuiRunPersistsFileName(
		@TempDir root: Path
	) {
		val context = persistingContext(root)
		val factory = mockk<SimulationContextFactory>()
		every { factory.createContext(any<EditingContext>()) } returns context
		getKoin().loadModules(listOf(module { single<SimulationContextFactory> { factory } }), allowOverride = true)
		frames.add(getKoin().get<Frame>())
		// Main refuses a file outside its working directory, so the fixture is copied below it.
		val xmlFile = fixtureCopyInWorkingDirectory()

		getKoin().get<Main>().loadSimWithGui(arrayOf("simgui", xmlFile.path))

		assertThat(awaitSnapshot(root).example, "example").isEqualTo(XML_FILE_NAME)
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("a run started from the Simulation menu persists the file's base name, not its path")
	fun menuStartPersistsFileName(
		@TempDir root: Path
	) {
		val context = persistingContext(root)
		val frame = getKoin().get<Frame>()
		frames.add(frame)
		val selected = File(root.toFile(), XML_FILE_NAME)

		SwingUtilities.invokeAndWait { startSimulationFromFile(frame, context, selected) }

		assertThat(awaitSnapshot(root).example, "example").isEqualTo(XML_FILE_NAME)
		SwingUtilities.invokeAndWait {
			assertThat(frame.modificationTracker.getCurrentFile(), "current file").isNull()
		}
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	@DisplayName("a run started without a name still persists example = null")
	fun unnamedStartPersistsNull(
		@TempDir root: Path
	) {
		val context = persistingContext(root)
		val frame = getKoin().get<Frame>()
		frames.add(frame)

		SwingUtilities.invokeAndWait {
			frame.setContext(context)
			frame.startSimulation()
		}

		assertThat(awaitSnapshot(root).example, "example").isNull()
	}

	/** A mock context that `finishAndPersist` writes to [root] when the run ends. */
	private fun persistingContext(root: Path): MockSimulationContext {
		val context = createMockShuntingContext()
		contexts.add(context)
		context.scope.declare<DispatcherRunRecorder>(
			DefaultDispatcherRunRecorder(
				runId = "gui-run-name",
				arm = DispatcherArm.RULE_BASED,
				params =
					RunParameters(
						tickPeriodMs = 500L,
						historyN = 10,
						temperature = 0.0,
						maxActionsPerTick = 3,
						model = "",
						seed = null
					)
			)
		)
		context.scope.declare(DispatcherRunConfig(runsRoot = root.toString()))
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))
		return context
	}

	private fun awaitSnapshot(root: Path): DispatcherRunSnapshot {
		val store = DefaultRunSnapshotStore(root)
		val deadline = System.currentTimeMillis() + WAIT_MS
		while (System.currentTimeMillis() < deadline) {
			store.readAll(root).singleOrNull()?.let { return it }
			Thread.sleep(POLL_MS)
		}
		throw AssertionError("No run JSON was written under $root within $WAIT_MS ms")
	}

	private fun fixtureCopyInWorkingDirectory(): File {
		val dir = FIXTURE_COPY_DIR.apply { mkdirs() }
		val file = File(dir, XML_FILE_NAME)
		TestFixtures.loadShuntingXml().use { input -> file.outputStream().use { input.copyTo(it) } }
		return file
	}
}
