/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.DispatcherRunSummaries
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.publish
import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DispatchDecisionListenerHub
import cz.vutbr.fit.interlockSim.sim.DispatcherModeState
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolution
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsConfig
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockShuntingContext
import cz.vutbr.fit.interlockSim.testutil.flushEDT
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.mp.KoinPlatformTools
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import cz.vutbr.fit.interlockSim.context.SimulationContext as SimContext

/**
 * The headless unit tests of [SimulationRunWiring] (PR #1430 coverage) on a real mock context:
 * the RUNNING captures (scope, recorder, adapter, run name), the dispatcher panel wiring, the
 * KPI dashboard's lifecycle, the STOPPED detach and finish with the load-bearing order
 * (detach first, Koog release after persistence), and the run-name transport to
 * [DispatcherRunSummaries.finishAndPersist] (Issue #1328).
 *
 * The hub and gateway collaborator wiring behind the panel is covered by their own suites in
 * `:core` (the hub's and the gateway's tests); here the wiring is asserted through the
 * observable panel state and the real hub's no-crash behavior. The recorder-fallback branch (a
 * run finished whose transition never captured a scope) is not reachable from an outside
 * caller: `onRunning` captures the scope and the recorder together, so that path stays covered
 * by the frame-level suites' late-STOPPED scenarios.
 */
@DisplayName("SimulationRunWiring (headless)")
class SimulationRunWiringTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private val context: SimContext by lazy { createMockShuntingContext() }

	/**
	 * A castable default context whose scope is THE scope the wiring captures (`context.scope`),
	 * so every declare/publish lands where `onRunning` reads and the panel's cast succeeds.
	 */
	private val castableContext: DefaultSimulationContext by lazy {
		mockk(relaxed = true) { every { scope } returns context.scope }
	}

	private val dispatcherControlPanel = DispatcherControlPanel()
	private val dashboardPanel = MetricsDashboardPanel()
	private val conflictResolutionPanel = ConflictResolutionPanel()
	private val wiring = SimulationRunWiring(dispatcherControlPanel, dashboardPanel, conflictResolutionPanel, null)

	private val runner: SimulationRunner by lazy {
		mockk(relaxed = true) {
			every { simulationContext } returns castableContext
		}
	}
	private val recorder: DispatcherRunRecorder by lazy { mockk(relaxed = true) }
	private val measuringAdapter: MeasuringPlanAdapter by lazy { mockk(relaxed = true) }
	private val metrics: MetricsCollectionService by lazy { mockk(relaxed = true) }
	private val metricsConfig: MetricsConfig by lazy {
		mockk(relaxed = true) { every { isEnabled(any()) } returns true }
	}

	/** A scope with no dispatcher bindings, for the backward-compatible wiring path. */
	private val emptyScope: Scope by lazy {
		KoinPlatformTools.defaultContext().get().createScope<Any>("wiring-test-empty-scope")
	}

	private val emptyContext: SimContext by lazy {
		mockk(relaxed = true) { every { scope } returns emptyScope }
	}

	private val emptyRunner: SimulationRunner by lazy {
		val emptyCastable: DefaultSimulationContext by lazy {
			mockk(relaxed = true) { every { scope } returns emptyScope }
		}
		mockk(relaxed = true) { every { simulationContext } returns emptyCastable }
	}

	@AfterEach
	fun tearDown() {
		SwingUtilities.invokeAndWait {
			dashboardPanel.stop(manual = false)
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("onRunning wires the panel with the scope's resolved mode state")
	fun onRunningWiresThePanel() {
		SwingUtilities.invokeAndWait { wiring.onRunning(context, runner) }

		val resolvedModeState: DispatcherModeState? =
			context.scope.getOrNull<DispatcherModeState>()
		assertThat(resolvedModeState).isNotNull()
		SwingUtilities.invokeAndWait {
			assertThat(dispatcherControlPanel.modeState).isEqualTo(resolvedModeState)
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("a scope without the dispatcher bindings keeps the panel disabled (backward compatible)")
	fun onRunningStaysBackwardCompatible() {
		SwingUtilities.invokeAndWait { wiring.onRunning(emptyContext, emptyRunner) }

		SwingUtilities.invokeAndWait {
			assertThat(dispatcherControlPanel.modeState).isNull()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("onRunning starts the KPI dashboard on the run's metrics service")
	fun onRunningStartsTheDashboard() {
		SwingUtilities.invokeAndWait {
			context.scope.declare(metrics)
			context.scope.declare(metricsConfig)
		}
		SwingUtilities.invokeAndWait { wiring.onRunning(context, runner) }

		// start polls synchronously once (MetricsDashboardPanel.start's first poll)
		SwingUtilities.invokeAndWait {
			verify(exactly = 1) { metrics.latestSnapshot() }
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("detachFromStaleRun clears the dispatcher panel state")
	fun detachClearsThePanelState() {
		SwingUtilities.invokeAndWait { wiring.onRunning(context, runner) }
		assertThat(dispatcherControlPanel.modeState).isNotNull()

		val hub = context.scope.getOrNull<DispatchDecisionListenerHub>()!!
		SwingUtilities.invokeAndWait {
			hub.onDecisionApplied(mockk(relaxed = true))
			wiring.detachFromStaleRun()
			hub.onDecisionApplied(mockk(relaxed = true))
		}

		SwingUtilities.invokeAndWait {
			assertThat(dispatcherControlPanel.modeState).isNull()
		}
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	@DisplayName("finishRun persists through the recorder and releases the agent after the finish")
	fun finishRunPersistsThenReleases() {
		SwingUtilities.invokeAndWait {
			context.scope.declare(recorder)
			context.scope.publish<MeasuringPlanAdapter>(measuringAdapter)
		}
		SwingUtilities.invokeAndWait { wiring.onRunning(context, runner) }

		SwingUtilities.invokeAndWait { wiring.finishRun(RunEndCause.MANUAL_STOP) }

		// The Koog release runs on a background thread after persistence: poll it first.
		verify(timeout = 5000, exactly = 1) { measuringAdapter.releaseAgent() }
		SwingUtilities.invokeAndWait {
			verify(exactly = 1) { measuringAdapter.logFinalSummary() }
			// #1096 review: the persistence happens BEFORE the release.
			verifyOrder {
				recorder.recordRailwayOutcome(any())
				recorder.finish(any())
				recorder.logFinalSummary()
				measuringAdapter.releaseAgent()
			}
		}
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	@DisplayName("the run name staged before RUNNING reaches finishAndPersist as the example name")
	fun stagedRunNameReachesThePersister() {
		SwingUtilities.invokeAndWait {
			context.scope.declare(recorder)
			wiring.stageRunName("staged-example")
			wiring.onRunning(context, runner)
		}

		mockkObject(DispatcherRunSummaries)
		try {
			every { DispatcherRunSummaries.finishAndPersist(any(), any(), eq("staged-example")) } answers { callOriginal() }
			SwingUtilities.invokeAndWait { wiring.finishRun(RunEndCause.MANUAL_STOP) }
			verify(exactly = 1) { DispatcherRunSummaries.finishAndPersist(any(), any(), "staged-example") }
		} finally {
			unmockkObject(DispatcherRunSummaries)
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("wireConflictResolution needs only the resolver: a conflict fills the panel, Apply stays disabled")
	fun wireConflictResolutionNeedsOnlyTheResolver() {
		// The scope holds the resolver and nothing else: no preference store (Issue #1454).
		val resolutions =
			listOf(
				ConflictResolution.HoldTrain(
					trainId = "Train-A",
					holdDurationSeconds = 30.0,
					affectedTrains = listOf("Train-A"),
					estimatedImpact = ConflictResolution.EstimatedImpact(30.0, "Hold Train-A for 30s")
				)
			)
		val resolver = mockk<ConflictResolver> { every { generateResolutions(any()) } returns resolutions }
		emptyScope.declare(resolver)
		val listener = slot<(ConflictDetectedEvent) -> Unit>()
		val conflictContext: SimContext =
			mockk(relaxed = true) {
				every { scope } returns emptyScope
				every { onConflictDetectedEvent(capture(listener)) } just Runs
			}

		SwingUtilities.invokeAndWait { wiring.wireConflictResolution(conflictContext) }
		val block = mockk<DynamicTrackBlock>(relaxed = true) { every { name } returns "Block-7" }
		val conflict = ConflictDetectedEvent(block, "Train-A", "Train-B", 12.5)
		listener.captured.invoke(conflict)
		flushEDT()

		verify(exactly = 1) { resolver.generateResolutions(conflict) }
		SwingUtilities.invokeAndWait {
			assertThat(conflictResolutionPanel.isVisible).isTrue()
			assertThat(conflictResolutionPanel.listModel.getElementAt(0)).isEqualTo(resolutions.single())
			assertThat(conflictResolutionPanel.applyButton.isEnabled).isFalse()
		}
	}
}
