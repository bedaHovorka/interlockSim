/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.observation.SignalView
import cz.vutbr.fit.interlockSim.dispatcher.observation.SwitchView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Acceptance-criteria tests for the SP2c.2 renderers (#825):
 *  - Three renderers behind one interface + composite (AC1)
 *  - Determinism: 100× identical bytes (AC3)
 *  - Layout invariant: STATION TOPOLOGY < RECENT TICKS < CURRENT STATE < WHAT YOU CAN DO NOW (AC4)
 *  - No-menu (C9): no numbered-option lines, no option/choose one/select (AC5)
 *  - Token-budget: char-length ceiling (AC6)
 *  - All doubles with explicit fixed pattern, no Double.toString (AC7)
 *  - SchematicRenderer degrades gracefully (AC8)
 *  - Tick-0 delta section = "(first tick)" (AC9)
 *  - Static topology block reuses StationTopologySerializer (AC10)
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
@DisplayName("SP2c.2 Renderers — acceptance criteria (#825)")
class ObservationRendererTest {
	// ── Fixtures ───────────────────────────────────────────────────────────────────────────

	private val ctx = RendererFixtures.tick41Context
	private val ctx0 = RendererFixtures.tick0Context
	private val topology = RendererFixtures.minimalTopology

	// ── AC1: three renderers + composite behind ObservationRenderer interface ─────────────

	@Nested
	@DisplayName("AC1 — interface contract")
	inner class InterfaceContract {
		@Test
		@DisplayName("CompactTextRenderer implements ObservationRenderer")
		fun compactTextRendererIsObservationRenderer() {
			val renderer: ObservationRenderer = CompactTextRenderer()
			assertThat(renderer.render(ctx).isNotEmpty()).isTrue()
		}

		@Test
		@DisplayName("DeltaRenderer implements ObservationRenderer")
		fun deltaRendererIsObservationRenderer() {
			val renderer: ObservationRenderer = DeltaRenderer()
			assertThat(renderer.render(ctx).isNotEmpty()).isTrue()
		}

		@Test
		@DisplayName("SchematicRenderer implements ObservationRenderer")
		fun schematicRendererIsObservationRenderer() {
			val renderer: ObservationRenderer = SchematicRenderer(topology)
			assertThat(renderer.render(ctx).isNotEmpty()).isTrue()
		}

		@Test
		@DisplayName("CompositeTickRenderer implements ObservationRenderer")
		fun compositeRendererIsObservationRenderer() {
			val renderer: ObservationRenderer = CompositeTickRenderer(listOf(CompactTextRenderer()))
			assertThat(renderer.render(ctx).isNotEmpty()).isTrue()
		}

		@Test
		@DisplayName("ObservationRenderer is a SAM interface: plain lambda satisfies it")
		fun isSamInterface() {
			val lambdaRenderer = ObservationRenderer { "hello" }
			assertThat(lambdaRenderer.render(ctx)).isEqualTo("hello")
		}
	}

	// ── AC3: determinism ──────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC3 — determinism (100×)")
	inner class Determinism {
		@RepeatedTest(100)
		@DisplayName("CompactTextRenderer: rendering the same context 100× yields identical bytes")
		fun compactTextDeterminism() {
			val first = CompactTextRenderer().render(ctx)
			val second = CompactTextRenderer().render(ctx)
			assertThat(first).isEqualTo(second)
		}

		@RepeatedTest(100)
		@DisplayName("DeltaRenderer: rendering the same context 100× yields identical bytes")
		fun deltaDeterminism() {
			val first = DeltaRenderer().render(ctx)
			val second = DeltaRenderer().render(ctx)
			assertThat(first).isEqualTo(second)
		}

		@RepeatedTest(100)
		@DisplayName("SchematicRenderer: rendering the same context 100× yields identical bytes")
		fun schematicDeterminism() {
			val renderer = SchematicRenderer(topology)
			val first = renderer.render(ctx)
			val second = renderer.render(ctx)
			assertThat(first).isEqualTo(second)
		}
	}

	// ── AC4: layout invariant ─────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC4 — layout invariant: STATION TOPOLOGY < RECENT TICKS < CURRENT STATE < WHAT YOU CAN DO NOW")
	inner class LayoutInvariant {
		@Test
		@DisplayName("full prompt assembles sections in the SP2c.2 layout order")
		fun layoutOrderIsPreserved() {
			val topologyText = StationTopologySerializer.toPromptText(topology)
			val schematic = SchematicRenderer(topology).render(ctx)
			val userMessage = CompactTextRenderer().render(ctx)
			val fullPrompt = topologyText + "\n" + schematic + "\n" + userMessage

			val idxTopology = fullPrompt.indexOf("STATION TOPOLOGY")
			val idxRecentTicks = fullPrompt.indexOf("RECENT TICKS")
			val idxCurrentState = fullPrompt.indexOf("CURRENT STATE")
			val idxWhatCanDo = fullPrompt.indexOf("WHAT YOU CAN DO NOW")

			assertThat(idxTopology).isLessThan(idxRecentTicks)
			assertThat(idxRecentTicks).isLessThan(idxCurrentState)
			assertThat(idxCurrentState).isLessThan(idxWhatCanDo)
		}
	}

	// ── AC5: no-menu (C9) ─────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC5 — no-menu (C9): no numbered options, no menu verbs")
	inner class NoMenuConstraint {
		private val numberedOptionPattern = Regex("""^\s*\d+[.)]\s""", RegexOption.MULTILINE)

		@Test
		@DisplayName("CompactTextRenderer output contains no numbered-option lines")
		fun compactTextNoNumberedOptions() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(numberedOptionPattern.containsMatchIn(output)).isEqualTo(false)
		}

		@Test
		@DisplayName("DeltaRenderer output contains no numbered-option lines")
		fun deltaNoNumberedOptions() {
			val output = DeltaRenderer().render(ctx)
			assertThat(numberedOptionPattern.containsMatchIn(output)).isEqualTo(false)
		}

		@Test
		@DisplayName("SchematicRenderer output contains no numbered-option lines")
		fun schematicNoNumberedOptions() {
			val output = SchematicRenderer(topology).render(ctx)
			assertThat(numberedOptionPattern.containsMatchIn(output)).isEqualTo(false)
		}

		@Test
		@DisplayName("CompactTextRenderer output contains no 'option' / 'choose one' / 'select' token")
		fun compactTextNoMenuVerbs() {
			val output = CompactTextRenderer().render(ctx).lowercase()
			assertThat(output).doesNotContain("option")
			assertThat(output).doesNotContain("choose one")
			assertThat(output).doesNotContain("select")
		}

		@Test
		@DisplayName("DeltaRenderer output contains no 'option' / 'choose one' / 'select' token")
		fun deltaNoMenuVerbs() {
			val output = DeltaRenderer().render(ctx).lowercase()
			assertThat(output).doesNotContain("option")
			assertThat(output).doesNotContain("choose one")
			assertThat(output).doesNotContain("select")
		}

		@Test
		@DisplayName("SchematicRenderer output contains no 'option' / 'choose one' / 'select' token")
		fun schematicNoMenuVerbs() {
			val output = SchematicRenderer(topology).render(ctx).lowercase()
			assertThat(output).doesNotContain("option")
			assertThat(output).doesNotContain("choose one")
			assertThat(output).doesNotContain("select")
		}
	}

	// ── AC6: token-budget ─────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC6 — token budget: char-length ceiling")
	inner class TokenBudget {
		/** 12 000 chars ≈ 3 000 tokens @ average 4 chars/token — safe ceiling for a 32 k context. */
		private val maxUserMessageChars = 12_000

		@Test
		@DisplayName("CompactTextRenderer output stays within char-length ceiling for tick-41 data")
		fun compactTextWithinBudget() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output.length).isLessThan(maxUserMessageChars)
		}

		@Test
		@DisplayName("DeltaRenderer output is substantially smaller than full state")
		fun deltaIsSmallerThanFullState() {
			val delta = DeltaRenderer().render(ctx)
			val full = CompactTextRenderer().render(ctx)
			assertThat(delta.length).isLessThan(full.length)
		}
	}

	// ── AC7: explicit double formatting ───────────────────────────────────────────────────

	@Nested
	@DisplayName("AC7 — all doubles formatted with explicit fixed pattern")
	inner class DoubleFormatting {
		@Test
		@DisplayName("CompactTextRenderer renders simTime with %.1f (no trailing decimals variance)")
		fun compactTextSimTimeFormatted() {
			val output = CompactTextRenderer().render(ctx)
			// simTime = 512.4 should appear as "512.4" not "512.3999999..." or "512.40000000..."
			assertThat(output).contains("512.4")
			assertThat(output).doesNotContain("512.3999")
			assertThat(output).doesNotContain("512.40000")
		}

		@Test
		@DisplayName("CompactTextRenderer renders velocity with %.1f")
		fun compactTextVelocityFormatted() {
			val output = CompactTextRenderer().render(ctx)
			// T-3 velocity = 6.4
			assertThat(output).contains("v=6.4")
		}

		@Test
		@DisplayName("CompactTextRenderer renders wait seconds with %.0f (integer seconds)")
		fun compactTextWaitSecondsFormatted() {
			val output = CompactTextRenderer().render(ctx)
			// T-3 waitSeconds = 0.0 → "wait 0s"
			assertThat(output).contains("wait 0s")
		}

		@Test
		@DisplayName("CompactTextRenderer renders longestQueuedWaitSecs with %.1f")
		fun compactTextWorkingMemoryFormatted() {
			val output = CompactTextRenderer().render(ctx)
			// longestQueuedWaitSecs = 14.4
			assertThat(output).contains("14.4")
		}

		@Test
		@DisplayName("CompactTextRenderer output is locale-independent: a comma-locale JVM still yields '.' decimals")
		fun compactTextLocaleIndependent() {
			// Guard for the AC7/Locale.ROOT fix. Under a comma-decimal locale (de_DE), the default
			// `String.format("%.1f", d)` would produce "6,4"/"512,4" and break byte-identical golden
			// output. The renderer must force Locale.ROOT so the separator is always '.'.
			val previous = Locale.getDefault()
			Locale.setDefault(Locale.GERMANY)
			try {
				val output = CompactTextRenderer().render(ctx)
				assertThat(output).contains("v=6.4")
				assertThat(output).contains("simTime 512.4")
				assertThat(output).doesNotContain("6,4")
				assertThat(output).doesNotContain("512,4")
			} finally {
				Locale.setDefault(previous)
			}
		}
	}

	// ── AC8: SchematicRenderer degrades gracefully ────────────────────────────────────────

	@Nested
	@DisplayName("AC8 — SchematicRenderer topology-parameterised degradation")
	inner class SchematicDegradation {
		@Test
		@DisplayName("schematic for empty topology → '(schematic unavailable for this topology)'")
		fun emptyTopologyDegrades() {
			val empty =
				StationTopology(
					inOuts = emptyList(),
					signals = emptyList(),
					switches = emptyList(),
					blocks = emptyList(),
					routes = emptyList()
				)
			val output = SchematicRenderer(empty).render(ctx)
			assertThat(output).contains("schematic unavailable for this topology")
		}

		@Test
		@DisplayName("schematic for no-route topology → '(schematic unavailable for this topology)'")
		fun noRoutesTopologyDegrades() {
			val noRoutes = topology.copy(routes = emptyList())
			val output = SchematicRenderer(noRoutes).render(ctx)
			assertThat(output).contains("schematic unavailable for this topology")
		}

		@Test
		@DisplayName("schematic for two-InOut topology → renders route arrows")
		fun twoInOutTopologyRendersRoutes() {
			val output = SchematicRenderer(topology).render(ctx)
			assertThat(output).contains("=== ASCII SCHEMATIC ===")
			assertThat(output).contains("A --> B")
			assertThat(output).contains("B --> A")
		}

		@Test
		@DisplayName("schematic shows block IDs in route order")
		fun schematicShowsBlockIds() {
			val output = SchematicRenderer(topology).render(ctx)
			assertThat(output).contains("kA")
			assertThat(output).contains("kB")
		}

		@Test
		@DisplayName("schematic content is identical across all RenderContexts (static section)")
		fun schematicIsStaticAcrossContexts() {
			val renderer = SchematicRenderer(topology)
			val first = renderer.render(ctx)
			val second = renderer.render(ctx0)
			assertThat(first).isEqualTo(second)
		}

		@Test
		@DisplayName("schematic for exactly 8 InOuts (maxRepresentableInOuts boundary) still renders route arrows")
		fun eightInOutsRendersRoutes() {
			// 8 is the boundary — `size > maxRepresentableInOuts` is false, so it must render.
			val eightInOuts =
				StationTopology(
					inOuts = listOf("A", "B", "C", "D", "E", "F", "G", "H"),
					signals = emptyList(),
					switches = emptyList(),
					blocks = listOf(BlockId("kA"), BlockId("kB")),
					routes = listOf(RouteDescriptor("A", "B", listOf(BlockId("kA"), BlockId("kB"))))
				)
			val output = SchematicRenderer(eightInOuts).render(ctx)
			assertThat(output).contains("=== ASCII SCHEMATIC ===")
			assertThat(output).contains("A --> B")
			assertThat(output).doesNotContain("schematic unavailable for this topology")
		}

		@Test
		@DisplayName("schematic for 9 InOuts (one over maxRepresentableInOuts) degrades to unavailable")
		fun nineInOutsDegrades() {
			// 9 is one over the boundary — `size > maxRepresentableInOuts` is true, so it must degrade.
			val nineInOuts =
				StationTopology(
					inOuts = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I"),
					signals = emptyList(),
					switches = emptyList(),
					blocks = listOf(BlockId("kA"), BlockId("kB")),
					routes = listOf(RouteDescriptor("A", "B", listOf(BlockId("kA"), BlockId("kB"))))
				)
			val output = SchematicRenderer(nineInOuts).render(ctx)
			assertThat(output).contains("schematic unavailable for this topology")
			assertThat(output).doesNotContain("A --> B")
		}
	}

	// ── AC9: tick-0 delta = "(first tick)" ────────────────────────────────────────────────

	@Nested
	@DisplayName("AC9 — tick-0 delta section")
	inner class TickZeroDelta {
		@Test
		@DisplayName("DeltaRenderer renders '(first tick)' when previous is null")
		fun deltaIsFirstTickWhenPreviousIsNull() {
			val output = DeltaRenderer().render(ctx0)
			assertThat(output).contains("(first tick)")
		}

		@Test
		@DisplayName("CompactTextRenderer includes '(first tick)' in its delta section on tick 0")
		fun compactTextIncludesFirstTickDelta() {
			val output = CompactTextRenderer().render(ctx0)
			assertThat(output).contains("(first tick)")
		}

		@Test
		@DisplayName("DeltaRenderer does NOT render '(first tick)' when previous is non-null")
		fun deltaIsNotFirstTickWhenPreviousPresent() {
			val output = DeltaRenderer().render(ctx)
			assertThat(output).doesNotContain("(first tick)")
		}
	}

	// ── CompactTextRenderer section-coverage tests ────────────────────────────────────────

	@Nested
	@DisplayName("CompactTextRenderer — section coverage for tick-41")
	inner class CompactTextSections {
		@Test
		@DisplayName("contains RECENT TICKS header")
		fun hasRecentTicksSection() = assertThat(CompactTextRenderer().render(ctx)).contains("=== RECENT TICKS ===")

		@Test
		@DisplayName("contains WORKING MEMORY header")
		fun hasWorkingMemorySection() = assertThat(CompactTextRenderer().render(ctx)).contains("=== WORKING MEMORY ===")

		@Test
		@DisplayName("contains applied outcomes header")
		fun hasAppliedOutcomesSection() = assertThat(CompactTextRenderer().render(ctx)).contains("applied outcomes since last tick:")

		@Test
		@DisplayName("contains CHANGED SINCE LAST TICK header")
		fun hasChangedSinceLastTickSection() =
			assertThat(CompactTextRenderer().render(ctx)).contains("=== CHANGED SINCE LAST TICK ===")

		@Test
		@DisplayName("contains CURRENT STATE header with tick number and simTime")
		fun hasCurrentStateSection() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("=== CURRENT STATE (tick 41, simTime 512.4) ===")
		}

		@Test
		@DisplayName("contains WHAT YOU CAN DO NOW header")
		fun hasAffordancesSection() = assertThat(CompactTextRenderer().render(ctx)).contains("=== WHAT YOU CAN DO NOW ===")

		@Test
		@DisplayName("contains history records (simTime formatted)")
		fun historyRecordsFormatted() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("simTime=483.1")
			assertThat(output).contains("simTime=496.2")
			assertThat(output).contains("simTime=504.8")
			assertThat(output).contains("outcome=LLM_ACTIONS")
			assertThat(output).contains("outcome=LLM_NO_OP")
		}

		@Test
		@DisplayName("contains active/queued capacity line")
		fun activeQueuedCapacityLine() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("active 1/2 ; queued 1")
		}

		@Test
		@DisplayName("contains T-3 RUNNING train line with key fields")
		fun t3RunningLine() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("T-3 RUNNING at kA -> B")
			assertThat(output).contains("v=6.4")
			assertThat(output).contains("a=0.4")
			assertThat(output).contains("ahead doB1=STOP")
			assertThat(output).contains("84m")
		}

		@Test
		@DisplayName("contains T-4 QUEUED train line with queued-since time")
		fun t4QueuedLine() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("T-4 QUEUED -> B")
			assertThat(output).contains("queued since 498.0")
			assertThat(output).contains("wait 14.4s")
		}

		@Test
		@DisplayName("renders Signal.FREE as 'GO' (not 'FREE')")
		fun signalFreeRenderedAsGo() {
			val output = CompactTextRenderer().render(ctx)
			// doA1 has Signal.FREE which should be "GO"
			assertThat(output).contains("doA1=GO")
		}

		@Test
		@DisplayName("contains switch vB as MAIN locked by T-3")
		fun switchLockRendered() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("vB=MAIN(locked by T-3)")
		}

		@Test
		@DisplayName("contains reservation: T-3 -> B via kA,kG,kJ,kB")
		fun reservationRendered() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("T-3 -> B via kA,kG,kJ,kB")
		}

		@Test
		@DisplayName("contains affordances for T-3 and T-4")
		fun affordancesRendered() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("T-3:")
			assertThat(output).contains("T-4:")
			assertThat(output).contains("approve_train")
			assertThat(output).contains("cancel_route")
			assertThat(output).contains("no_op: always applicable")
		}

		@Test
		@DisplayName("no_op is always the last affordance line")
		fun noOpIsLast() {
			val output = CompactTextRenderer().render(ctx)
			val lastLine =
				output.trimEnd().lines().last()
			assertThat(lastLine).contains("no_op: always applicable")
		}

		@Test
		@DisplayName("applied outcomes section shows '(none)' when empty")
		fun appliedOutcomesNone() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("(none)")
		}

		@Test
		@DisplayName("applied outcomes section lists each outcome when non-empty")
		fun appliedOutcomesPopulated() {
			val obs =
				RendererFixtures.observationTick41.copy(
					appliedOutcomes = listOf(
						AppliedOutcome.Approved(
							trainId = "T-3",
							admitted = true,
							id = CommandId(40L),
							tickIndex = 40L
						)
					)
				)
			val output = CompactTextRenderer().render(ctx.copy(observation = obs))
			assertThat(output).contains("approve_train T-3 : ADMITTED")
		}

		@Test
		@DisplayName("working memory fields are all present")
		fun workingMemoryFieldsPresent() {
			val output = CompactTextRenderer().render(ctx)
			assertThat(output).contains("consecutive_no_op_ticks: 0")
			assertThat(output).contains("longest_queued_wait_secs: 14.4")
			assertThat(output).contains("blocked_train_count: 0")
			assertThat(output).contains("last_tick_outcome: LLM_ACTIONS")
		}
	}

	// ── DeltaRenderer tests ───────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("DeltaRenderer — delta section for tick-41")
	inner class DeltaRendererTests {
		@Test
		@DisplayName("contains CHANGED SINCE LAST TICK header")
		fun hasDeltaHeader() = assertThat(DeltaRenderer().render(ctx)).contains("=== CHANGED SINCE LAST TICK ===")

		@Test
		@DisplayName("reports T-3 phase change from HELD to RUNNING")
		fun reportsPhaseDelta() {
			val output = DeltaRenderer().render(ctx)
			assertThat(output).contains("T-3")
			assertThat(output).contains("RUNNING")
			assertThat(output).contains("HELD")
		}

		@Test
		@DisplayName("reports kG state change FREE->RESERVED(T-3)")
		fun reportsBlockDeltaKG() {
			val output = DeltaRenderer().render(ctx)
			assertThat(output).contains("kG")
			assertThat(output).contains("FREE->RESERVED(T-3)")
		}

		@Test
		@DisplayName("reports kJ state change FREE->RESERVED(T-3)")
		fun reportsBlockDeltaKJ() {
			val output = DeltaRenderer().render(ctx)
			assertThat(output).contains("kJ")
			assertThat(output).contains("FREE->RESERVED(T-3)")
		}

		@Test
		@DisplayName("renders '(no changes)' when observations are identical")
		fun noChangesWhenSame() {
			val identical = ctx.copy(previous = ctx.observation)
			val output = DeltaRenderer().render(identical)
			assertThat(output).contains("(no changes)")
		}

		@Test
		@DisplayName("reports switch position change MAIN(free)->BRANCH(free)")
		fun reportsSwitchPositionDelta() {
			val prev = RendererFixtures.observationTick41.copy(switches = listOf(SwitchView("vA", RailSwitch.Conf.MAIN, null)))
			val curr = RendererFixtures.observationTick41.copy(switches = listOf(SwitchView("vA", RailSwitch.Conf.BRANCH, null)))
			val output = DeltaRenderer().render(RenderContext(curr, prev, emptyList(), WorkingMemory.EMPTY, emptyList()))
			assertThat(output).contains("switches:")
			assertThat(output).contains("vA MAIN(free)->BRANCH(free)")
		}

		@Test
		@DisplayName("reports switch lock change free->locked by T-3 (same position)")
		fun reportsSwitchLockDelta() {
			val prev = RendererFixtures.observationTick41.copy(switches = listOf(SwitchView("vA", RailSwitch.Conf.MAIN, null)))
			val curr = RendererFixtures.observationTick41.copy(switches = listOf(SwitchView("vA", RailSwitch.Conf.MAIN, "T-3")))
			val output = DeltaRenderer().render(RenderContext(curr, prev, emptyList(), WorkingMemory.EMPTY, emptyList()))
			assertThat(output).contains("vA MAIN(free)->MAIN(locked by T-3)")
		}

		@Test
		@DisplayName("reports signal aspect change STOP->FREE")
		fun reportsSignalDelta() {
			val prev = RendererFixtures.observationTick41.copy(signals = listOf(SignalView("doA1", Signal.STOP, null, null)))
			val curr = RendererFixtures.observationTick41.copy(signals = listOf(SignalView("doA1", Signal.FREE, null, null)))
			val output = DeltaRenderer().render(RenderContext(curr, prev, emptyList(), WorkingMemory.EMPTY, emptyList()))
			assertThat(output).contains("signals:")
			assertThat(output).contains("doA1 STOP->FREE")
		}

		@Test
		@DisplayName("reports a newly-appeared train as (new/<phase>)")
		fun reportsNewTrainDelta() {
			val prev = RendererFixtures.observationTick41.copy(trains = emptyList())
			val curr =
				RendererFixtures.observationTick41.copy(
					trains = listOf(TrainView("T-5", TrainPhase.RUNNING, "kA", 5.0, 0.0, "B", null, null, 0.0, null, 0.0))
				)
			val output = DeltaRenderer().render(RenderContext(curr, prev, emptyList(), WorkingMemory.EMPTY, emptyList()))
			assertThat(output).contains("T-5(new/RUNNING)")
		}

		@Test
		@DisplayName("reports a disappeared train as (exited)")
		fun reportsExitedTrainDelta() {
			val prev =
				RendererFixtures.observationTick41.copy(
					trains = listOf(TrainView("T-6", TrainPhase.RUNNING, "kA", 5.0, 0.0, "B", null, null, 0.0, null, 0.0))
				)
			val curr = RendererFixtures.observationTick41.copy(trains = emptyList())
			val output = DeltaRenderer().render(RenderContext(curr, prev, emptyList(), WorkingMemory.EMPTY, emptyList()))
			assertThat(output).contains("T-6(exited)")
		}
	}

	// ── CompositeTickRenderer tests ───────────────────────────────────────────────────────

	@Nested
	@DisplayName("CompositeTickRenderer — part concatenation")
	inner class CompositeTests {
		@Test
		@DisplayName("empty parts list produces empty string")
		fun emptyPartsProducesEmptyString() {
			val composite = CompositeTickRenderer(emptyList())
			assertThat(composite.render(ctx)).isEqualTo("")
		}

		@Test
		@DisplayName("single part produces that part's output")
		fun singlePart() {
			val delta = DeltaRenderer()
			val composite = CompositeTickRenderer(listOf(delta))
			assertThat(composite.render(ctx)).isEqualTo(delta.render(ctx))
		}

		@Test
		@DisplayName("multiple parts produce concatenated output")
		fun multipleParts() {
			val delta = DeltaRenderer()
			val compact = CompactTextRenderer()
			val composite = CompositeTickRenderer(listOf(delta, compact))
			val expected = delta.render(ctx) + compact.render(ctx)
			assertThat(composite.render(ctx)).isEqualTo(expected)
		}

		@Test
		@DisplayName("composite with schematic + compact contains both headers")
		fun schematicPlusCompact() {
			val composite = CompositeTickRenderer(listOf(SchematicRenderer(topology), CompactTextRenderer()))
			val output = composite.render(ctx)
			assertThat(output).contains("=== ASCII SCHEMATIC ===")
			assertThat(output).contains("=== CURRENT STATE")
		}
	}

	// ── Golden file tests ─────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Golden file — byte-identical output for recorded tick-41 data")
	inner class GoldenFile {
		/**
		 * Loads the golden file at [resourcePath] from the test classpath, normalised to LF
		 * line endings. Fails loudly (throws) if the resource is missing — a silently-absent
		 * golden file would let a byte-identical regression go unnoticed, so the missing case is
		 * a test infrastructure failure, not a soft pass.
		 */
		private fun loadGolden(resourcePath: String): String =
			requireNotNull(
				javaClass.classLoader
					.getResourceAsStream(resourcePath)
					?.bufferedReader(Charsets.UTF_8)
					?.use { it.readText() }
					?.replace("\r\n", "\n")
					?.replace("\r", "\n")
			) { "$resourcePath not found on test classpath — golden resource is missing" }

		private fun lf(s: String): String = s.replace("\r\n", "\n").replace("\r", "\n")

		@Test
		@DisplayName("CompactTextRenderer golden file matches (canonical, always-on renderer)")
		fun compactTextGolden() {
			val actual = lf(CompactTextRenderer().render(ctx))
			assertThat(actual).isEqualTo(loadGolden("compact_text_renderer_golden.txt"))
		}

		@Test
		@DisplayName("DeltaRenderer golden file matches")
		fun deltaGolden() {
			val actual = lf(DeltaRenderer().render(ctx))
			assertThat(actual).isEqualTo(loadGolden("delta_renderer_golden.txt"))
		}

		@Test
		@DisplayName("SchematicRenderer golden file matches")
		fun schematicGolden() {
			val twoRoute =
				StationTopology(
					inOuts = listOf("A", "B"),
					signals = emptyList(),
					switches = emptyList(),
					blocks = listOf(BlockId("kA"), BlockId("kB")),
					routes =
						listOf(
							RouteDescriptor("A", "B", listOf(BlockId("kA"), BlockId("kB"))),
							RouteDescriptor("B", "A", listOf(BlockId("kB"), BlockId("kA")))
						)
				)
			val actual = lf(SchematicRenderer(twoRoute).render(ctx))
			assertThat(actual).isEqualTo(loadGolden("schematic_renderer_golden.txt"))
		}
	}
}
