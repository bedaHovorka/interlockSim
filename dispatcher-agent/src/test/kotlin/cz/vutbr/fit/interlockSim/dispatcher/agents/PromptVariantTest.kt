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
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The [PromptVariant] seam and the [PromptVariant.REVISED] prompt contract (Issue #834, SP2c.11).
 *
 * ## Division of labour with [KoogAgentFactoryTest]
 *
 * [KoogAgentFactoryTest] drives the *assembled* prompt through [KoogAgentFactory.createAgent] and
 * owns the pinned-phrase contract for [PromptVariant.BASELINE] — every one of those assertions
 * still runs unchanged, which is what proves the baseline reproduces PR #896's prompt byte for
 * byte. This class owns everything variant-shaped: the parser, the properties that must hold for
 * *every* variant, and the explicit contract for the revision.
 *
 * Assertions here call [KoogAgentFactory.buildSystemPrompt] directly rather than through
 * `createAgent`. The two differ only by the appended topology block, which is variant-independent
 * by construction (#834's own acceptance criterion keeps [StationTopologySerializer] out of the
 * prompt rebuild), so testing the instruction half directly says exactly what it means and needs
 * no XML fixture, Koin scope or mock port to say it. [KoogAgentFactoryTest] separately pins that
 * `createAgent` actually threads the constructor's variant into that call.
 *
 * ## Nothing here claims the revision is better
 *
 * Every assertion below is either a property [PromptVariant.REVISED] must *preserve* (each one
 * bought with a measured failure, listed in [KoogAgentFactory]'s KDoc) or a property it must
 * *add*. There is deliberately no assertion of the form "REVISED is an improvement": that is the
 * sweep's verdict to give, and a test asserting it would be asserting a conclusion from no data.
 *
 * @since Issue #834 (SP2c.11 — prompt rebuild as a swept parameter)
 */
@DisplayName("SP2c.11 — PromptVariant seam and the REVISED prompt contract (#834)")
class PromptVariantTest {
	private val cap = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS

	private fun promptFor(
		variant: PromptVariant,
		maxActions: Int = 3
	): String = KoogAgentFactory.buildSystemPrompt(maxActions, variant)

	@Nested
	@DisplayName("PromptVariant.parse")
	inner class Parsing {
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("every variant round-trips through its own name")
		fun everyVariantRoundTrips(variant: PromptVariant) {
			assertThat(PromptVariant.parse(variant.name)).isEqualTo(variant)
		}

		/**
		 * The value travels through a shell command line and a `.properties` file before it gets
		 * here, so rejecting `revised` while accepting `REVISED` would cost an unattended sweep for
		 * no measurement reason.
		 */
		@Test
		@DisplayName("names are matched case-insensitively and ignore surrounding whitespace")
		fun parsingIsForgivingAboutCaseAndPadding() {
			assertThat(PromptVariant.parse("revised")).isEqualTo(PromptVariant.REVISED)
			assertThat(PromptVariant.parse("  BaseLine  ")).isEqualTo(PromptVariant.BASELINE)
		}

		/**
		 * `null` rather than a thrown exception or a silently-substituted default: the caller
		 * ([cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig]) owns the fallback *and* its
		 * WARN, which is what makes a malformed variant name behave like every other malformed `-D`.
		 */
		@Test
		@DisplayName("an unknown, blank or absent name yields null rather than a default or a throw")
		fun unknownNamesYieldNull() {
			assertThat(PromptVariant.parse("REVISD")).isNull()
			assertThat(PromptVariant.parse("")).isNull()
			assertThat(PromptVariant.parse("   ")).isNull()
			assertThat(PromptVariant.parse(null)).isNull()
		}

		/**
		 * Flipping this is a measurement decision, not a refactoring: with a non-BASELINE default,
		 * every run that never asked for a variant would silently change arms.
		 *
		 * Note (Issue #834 review finding #4): this pins the **parse-failure fallback**, not the
		 * shipped default. The shipped default is `REVISED`, set in `dispatcher-defaults.properties`;
		 * `PromptVariant.DEFAULT` is only what a malformed or absent value falls back to.
		 */
		@Test
		@DisplayName("the default variant is BASELINE, so an unconfigured run keeps PR #896's prompt")
		fun defaultIsBaseline() {
			assertThat(PromptVariant.DEFAULT).isEqualTo(PromptVariant.BASELINE)
		}
	}

	@Nested
	@DisplayName("Properties every variant must hold")
	inner class EveryVariant {
		/**
		 * The stated budget is read per call from the [SinkHolder] that enforces it, so it can never
		 * drift from what `SinkHolder.tryEmit` refuses. #847 measured 146 `ACTION_LIMIT_EXCEEDED`
		 * rejections across 20 runs against a prompt that never stated the budget at all; a variant
		 * that hardcoded `3` here would pass silently at the default and be wrong for every swept
		 * cell that changes the cap.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("states the per-tick action budget from the value passed in, never a literal")
		fun statesTheBudgetFromTheArgument(variant: PromptVariant) {
			assertThat(promptFor(variant, maxActions = 2)).contains("At most 2 actions besides no_op")
			assertThat(promptFor(variant, maxActions = 2)).doesNotContain("At most 3 actions besides no_op")
			assertThat(promptFor(variant, maxActions = 3)).contains("At most 3 actions besides no_op")
		}

		/**
		 * P8 (#822): the same variant and inputs produce a byte-identical prompt. The seam must not
		 * have introduced any per-call nondeterminism (a set iteration order, a timestamp, a hash),
		 * because the whole prompt half of P8's determinism guarantee rests on this.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("is byte-identical across repeated builds with the same inputs (P8)")
		fun isDeterministic(variant: PromptVariant) {
			assertThat(promptFor(variant)).isEqualTo(promptFor(variant))
			assertThat(promptFor(variant, maxActions = 7)).isEqualTo(promptFor(variant, maxActions = 7))
		}

		/**
		 * C9 (#822): dash lists only, never numbered, and none of the menu verbs.
		 * [LivePromptNoMenuTest] applies the same checks to the fully assembled prompt for both
		 * variants; this is the cheap per-variant guard on the instruction half alone.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("contains no numbered-option line and no menu verb (C9)")
		fun hasNoMenuArtifacts(variant: PromptVariant) {
			NoMenuAssertions.assertNoMenuArtifacts(promptFor(variant))
		}

		/**
		 * A literal train name in the prompt is copied verbatim by the model. Because nothing on the
		 * live path validates `trainName`, one copied `"T1"` reserved real blocks for a train that
		 * did not exist and bricked a whole run — see
		 * [StationTopologySerializerTest.promptCarriesNoCopyableTrainName], the deterministic guard
		 * for the topology half. This is the same guard for the instruction half, per variant.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("carries no concrete train name for the model to copy")
		fun carriesNoCopyableTrainName(variant: PromptVariant) {
			val prompt = promptFor(variant)

			assertThat(prompt).doesNotContain("\"T1\"")
			assertThat(prompt).doesNotContain("trainName=\"")
			assertThat(prompt).doesNotContain("trainId=\"")
		}

		/**
		 * The four-tool surface `ActuatorToolSurface.assertExactly` holds the registry to, stated in
		 * the prompt — plus the fact that no tool sets a signal or switch directly, which is what
		 * stops the model reaching for one.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("names exactly the four actuator tools and denies any direct signal/switch tool")
		fun namesTheActuatorSurface(variant: PromptVariant) {
			val prompt = promptFor(variant)

			assertThat(prompt).contains(
				"The only actuator tools available are approve_train, request_route, cancel_route, and no_op"
			)
			assertThat(prompt).contains("there is no tool to set a signal aspect or switch position directly")
		}

		/**
		 * #847 recorded zero `ENDPOINT_IS_BLOCK_ID` and zero `UNKNOWN_ENDPOINT` rejections across 20
		 * runs with this correction present. Every variant states it; only the *number of times* it
		 * is stated is allowed to differ.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("states that request_route endpoints are InOut/Signal names and never a Block ID")
		fun statesTheEndpointVocabulary(variant: PromptVariant) {
			assertThat(promptFor(variant)).contains("never a Block ID")
		}

		/** #893's fix, and what took journeys per run from 0 to 1-3. */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("carries the NEXT SECTION routing procedure, including the no-NEXT-SECTION case")
		fun carriesTheNextSectionProcedure(variant: PromptVariant) {
			val prompt = promptFor(variant)

			assertThat(prompt).contains("NEXT SECTION")
			assertThat(prompt.lowercase())
				.contains("train with no next section line gets no route request this tick")
		}

		/** Admission before routing, with the concrete concurrent-train ceiling spelled out. */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("states that admission comes first, with the concrete concurrent-train cap")
		fun statesAdmissionFirst(variant: PromptVariant) {
			val prompt = promptFor(variant)

			assertThat(prompt.lowercase()).contains("admission comes first")
			assertThat(prompt).contains("fewer than $cap trains are currently active")
			assertThat(prompt).contains("up to $cap total active")
		}

		/**
		 * Before the turn-termination affordance the model never ended a turn: zero `no_op` calls
		 * ever recorded, and Koog iteration exhaustion in 3-4 of 5 cycles. Whatever a variant does
		 * with the wording, it must still say that a plain-text reply is what ends a tick.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("tells the model that only a plain-text reply ends the tick")
		fun statesHowATickEnds(variant: PromptVariant) {
			assertThat(promptFor(variant)).contains("Only a plain-text reply ends the tick")
		}

		/** The anti-churn rule, kept in every variant. */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("tells the model to stop acting on a train rejected twice in one tick")
		fun statesTheTwoRejectionsRule(variant: PromptVariant) {
			assertThat(promptFor(variant)).contains(
				"If a tool call for a train is rejected twice in one tick, stop acting on that train " +
					"and move on or reply."
			)
		}

		/** One of #896's two terminal rejection directives. */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("tells the model never to approve_train a train already listed as active")
		fun statesTheNeverApproveActiveDirective(variant: PromptVariant) {
			assertThat(promptFor(variant)).contains("Never call approve_train for a train already listed as active")
		}

		/**
		 * Once the budget is spent, further actions are refused and the turn should end.
		 *
		 * A bare `contains("refused")` would be satisfied by the unrelated "Repeating an action
		 * already in force is refused" rule, which both variants carry — so this asserts the
		 * *budget* sentence specifically, by requiring "budget" and "refused" on one line.
		 *
		 * That line must also not claim `no_op` itself gets refused: `SinkHolder.tryEmit` always
		 * emits a `NoOp` and never counts it against the cap, so "every further tool call is
		 * refused" would be false for the one tool the model most needs *after* the budget is
		 * spent — and would contradict the same sentence's own "no_op never counts against that
		 * budget" clause.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("states that actions past the budget are refused, without refusing no_op")
		fun statesThatActionsPastTheBudgetAreRefused(variant: PromptVariant) {
			val budgetLines =
				promptFor(variant)
					.lineSequence()
					.filter { it.contains("budget") && it.contains("refused") }
					.toList()

			assertThat(budgetLines).isNotEmpty()
			budgetLines
				.filter { it.contains("no_op never counts against that budget") }
				.forEach { line ->
					// A line that exempts no_op from the budget must not, in the same breath, refuse
					// "every further tool call" — no_op is a tool call, and SinkHolder.tryEmit always
					// emits it. Refusing actions is the true and non-contradictory claim.
					assertThat(line).doesNotContain("tool call this tick is refused")
					assertThat(line).doesNotContain("tool calls this tick are refused")
				}
		}
	}

	@Nested
	@DisplayName("The REVISED contract")
	inner class RevisedContract {
		private val revised = promptFor(PromptVariant.REVISED)
		private val baseline = promptFor(PromptVariant.BASELINE)

		/** Without this the enum would be decorative and the sweep axis would measure nothing. */
		@Test
		@DisplayName("REVISED is a different prompt from BASELINE")
		fun revisedDiffersFromBaseline() {
			assertThat(revised).isNotEqualTo(baseline)
		}

		/**
		 * Length is a measured cost, not a style preference: cycle latency (60-100 s against
		 * `qwen2.5:7b-instruct`) is the binding constraint on the whole LLM arm, and every token of
		 * system prompt is re-read every cycle. A longer cycle means more control ticks coalesced
		 * away by `DefaultSnapshotSignal`'s at-most-one-pending rule, hence fewer decisions per run
		 * — the same mechanism by which #847 measured the history block costing roughly a fifth of
		 * the decision rate.
		 *
		 * The bar here is deliberately "shorter", not a pinned number: a token count would be a
		 * brittle assertion about a tokenizer this repository does not own. What must not silently
		 * happen is the revision growing back past its control.
		 */
		@Test
		@DisplayName("REVISED is shorter than BASELINE — length is the change with measured leverage")
		fun revisedIsShorterThanBaseline() {
			assertThat(baseline.length).isGreaterThan(revised.length)
			assertThat(baseline.split(Regex("\\s+")).size).isGreaterThan(revised.split(Regex("\\s+")).size)
		}

		/**
		 * The property Task 1 of #834 made worth stating: an **idle-station** cycle with no emissions
		 * is now a scored success ([cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome.LLM_NO_OP])
		 * rather than a rule-based-fallback failure — that outcome is scoped to the idle station, not
		 * to any tick without tool calls. BASELINE's catch-all says only "call no_op with a brief
		 * reason" — never that replying afterwards ends the tick, nor that this is the expected
		 * result rather than a failure to act. #896 measured 2-7 ticks per run ending in a bare text
		 * reply instead of the taught `no_op`.
		 */
		@Test
		@DisplayName("REVISED names the correct idle output explicitly: one no_op, then a reply")
		fun revisedNamesTheIdlePath() {
			assertThat(revised).contains("call no_op once with a brief reason and then reply")
			assertThat(revised).contains("not a failure to act")
		}

		/**
		 * The catch-all must stay **unconditional**, as [PromptVariant.BASELINE]'s
		 * "- Otherwise, call no_op with a brief reason." is.
		 *
		 * An earlier cut of this revision gated it on the empty-station case ("no queued trains, and
		 * no active train with a NEXT SECTION line"), which leaves a routine state with *no bullet
		 * firing at all*: queued trains waiting, the station already at `cap` active, and no active
		 * train with a NEXT SECTION line. Admission's guard is false, routing's is false,
		 * cancelling's is false, and a station-idle guard is false precisely because trains are
		 * queued. The model then replies in plain text with no emission and the tick is scored
		 * `RULE_FALLBACK` — the outcome #834 exists to reduce. This test is the regression lock on
		 * that reasoning.
		 */
		@Test
		@DisplayName("REVISED's final procedure bullet is an unconditional catch-all, not an idle-only case")
		fun revisedCatchAllIsUnconditional() {
			// The last dash bullet of the *procedure* — the rules section that follows is dash-bulleted
			// too, so the search must stop at its header.
			val catchAll =
				revised
					.substringBefore("Rules that never bend:")
					.lineSequence()
					.last { it.startsWith("- ") }

			assertThat(catchAll).contains("Otherwise")
			assertThat(catchAll).contains("call no_op once")
			// The guard clauses of the narrowed version, which must not have come back.
			assertThat(catchAll.contains("no queued trains")).isFalse()
			assertThat(catchAll.contains("station is idle")).isFalse()
		}

		/**
		 * BASELINE states the budget three times (a standalone sentence, the "Once the per-tick
		 * action budget is spent" rule, and the "never more than N" clause of the termination rule).
		 * The revision states it once. This test is what makes that claim checkable rather than a
		 * commit-message assertion, and it would fail if a future edit reintroduced a second copy.
		 */
		@Test
		@DisplayName("REVISED states the action budget exactly once, where BASELINE states it three times")
		fun revisedStatesTheBudgetOnce() {
			assertThat(occurrencesOf(revised, "besides no_op")).isEqualTo(1)
			// BASELINE's other two statements of the same budget, in different words.
			assertThat(baseline).contains("never more than")
			assertThat(baseline).contains("Once the per-tick action budget is spent")
			assertThat(revised.contains("never more than")).isFalse()
			assertThat(revised.contains("Once the per-tick action budget is spent")).isFalse()
		}

		/**
		 * Same claim for name discipline: BASELINE carries a STATION TOPOLOGY paragraph, a
		 * verbatim-train-id paragraph and a "Copy every name character-for-character" rule; the
		 * revision merges them into one sentence that keeps both load-bearing clauses. #847 recorded
		 * zero `UNKNOWN_ENDPOINT`, `ENDPOINT_IS_BLOCK_ID`, `UNKNOWN_TRAIN` and `BLANK_ARGUMENT`
		 * rejections, so this is a solved problem stated once instead of three times — not a relaxed
		 * one. The two clauses are asserted present above, in [EveryVariant].
		 */
		@Test
		@DisplayName("REVISED states the name-copying rule exactly once")
		fun revisedStatesNameCopyingOnce() {
			assertThat(occurrencesOf(revised, "character-for-character")).isEqualTo(1)
			assertThat(occurrencesOf(baseline, "character-for-character")).isEqualTo(1)
			// BASELINE's second statement of the same rule, in different words, is gone.
			assertThat(revised.contains("Never invent, abbreviate, or guess a name")).isFalse()
			assertThat(revised).contains("never invent, abbreviate, or infer one")
		}

		/**
		 * "Always prioritize safety" is the one sentence in BASELINE that no measurement asked for
		 * and that names no action the model can take. Everything the model must actually not do is
		 * stated as a rule elsewhere.
		 */
		@Test
		@DisplayName("REVISED drops the unactionable safety exhortation")
		fun revisedDropsTheUnactionableExhortation() {
			assertThat(baseline).contains("Always prioritize safety")
			assertThat(revised.contains("Always prioritize safety")).isFalse()
		}

		/**
		 * The turn-termination sentence moved into the opening framing rather than sitting last in
		 * the rules: it is the instruction whose absence broke every cycle, so it now occupies the
		 * most salient position in the prompt while costing fewer tokens than the rule it replaces.
		 */
		@Test
		@DisplayName("REVISED states how a tick ends in its opening paragraph")
		fun revisedStatesTerminationUpFront() {
			assertThat(revised.lineSequence().first()).contains("Only a plain-text reply ends the tick")
		}

		private fun occurrencesOf(
			text: String,
			needle: String
		): Int {
			var count = 0
			var index = text.indexOf(needle)
			while (index >= 0) {
				count++
				index = text.indexOf(needle, index + needle.length)
			}
			return count
		}
	}
}
