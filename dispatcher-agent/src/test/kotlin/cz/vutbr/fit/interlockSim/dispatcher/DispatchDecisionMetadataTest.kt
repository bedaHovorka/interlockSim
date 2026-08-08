/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Exhaustive-branch coverage for the SP2c.17 (#840) decision-metadata projections
 * [commandTypeName] and [extractTrainId] — top-level `internal` extensions over the sealed
 * [DispatchDecision] type. Both are `when` *expressions* over all 8 subtypes, so the compiler
 * already enforces exhaustiveness; these tests pin the per-subtype string/identifier mapping so
 * a future subtype addition or a rename is caught here rather than producing a wrong
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome.DroppedInvalid] payload.
 *
 * @since Issue #840 (SP2c.17 — correlated async outcome channel)
 */
@DisplayName("DispatchDecision metadata projections — commandTypeName / extractTrainId (#840)")
class DispatchDecisionMetadataTest {
	@DisplayName("commandTypeName() maps every DispatchDecision subtype to its tool name")
	@ParameterizedTest(name = "{0} -> \"{1}\"")
	@MethodSource("commandTypeNameCases")
	fun commandTypeNameMapsEverySubtype(
		decision: DispatchDecision,
		expectedName: String
	) {
		assertThat(decision.commandTypeName()).isEqualTo(expectedName)
	}

	@DisplayName("extractTrainId() maps every DispatchDecision subtype to its train identifier")
	@ParameterizedTest(name = "{0} -> \"{1}\"")
	@MethodSource("extractTrainIdCases")
	fun extractTrainIdMapsEverySubtype(
		decision: DispatchDecision,
		expectedTrainId: String
	) {
		assertThat(decision.extractTrainId()).isEqualTo(expectedTrainId)
	}

	companion object {
		@JvmStatic
		fun commandTypeNameCases(): List<Arguments> =
			decisionsWithExpected().map { (decision, name, _) ->
				Arguments.of(decision, name)
			}

		@JvmStatic
		fun extractTrainIdCases(): List<Arguments> =
			decisionsWithExpected().map { (decision, _, trainId) ->
				Arguments.of(decision, trainId)
			}

		/**
		 * One example of every [DispatchDecision] subtype paired with its expected
		 * [commandTypeName] and [extractTrainId] outputs. Adding a subtype to the sealed class
		 * without extending this table leaves a compile error in [commandTypeName]/[extractTrainId]
		 * first; once added there, this table should be extended to keep the mapping pinned.
		 */
		private fun decisionsWithExpected(): List<Triple<DispatchDecision, String, String>> =
			listOf(
				Triple(DispatchDecision.RequestRoute("T-1", "zA", "InOut-B"), "request_route", "T-1"),
				// The LLM speaks `cancel_route` (SP2c.6, Issue #829); the rule engine speaks
				// `ReleaseRoute`. commandTypeName() projects the LLM-facing tool name, so it must
				// return "cancel_route" — not the retired "release_route".
				Triple(DispatchDecision.ReleaseRoute("T-2"), "cancel_route", "T-2"),
				Triple(DispatchDecision.ApproveTrain("T-3"), "approve_train", "T-3"),
				Triple(DispatchDecision.ReservePath("T-4", "zA", "InOut-B"), "reserve_path", "T-4"),
				Triple(DispatchDecision.HoldTrain("T-5", 1.0), "hold_train", "T-5"),
				Triple(DispatchDecision.SetSignalAspect("doA1", Signal.STOP), "set_signal_aspect", ""),
				Triple(DispatchDecision.SetSwitchPosition("vA", RailSwitch.Conf.MAIN), "set_switch_position", ""),
				Triple(DispatchDecision.NoAction, "no_action", "")
			)
	}
}
