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

import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the common [DispatchDecision.kind] and [DispatchDecision.trainName] members (Issue #969):
 * one instance per subtype, its [DispatchDecisionKind], and the train it names.
 *
 * The [DispatchDecisionKind.commandType] strings are the ones `:dispatcher-agent`
 * `commandTypeName()` returned before #969; the [DispatchDecisionKind.displayName] strings are
 * the ones `:desktop-ui` `SemiAutoApprovalDialog.decisionTypeName` returned before #969.
 */
class DispatchDecisionKindTest {
	private data class Row(
		val decision: DispatchDecision,
		val kind: DispatchDecisionKind,
		val trainName: String?,
		val commandType: String,
		val displayName: String
	)

	private val rows =
		listOf(
			Row(
				DispatchDecision.ApproveTrain("T-1"),
				DispatchDecisionKind.APPROVE_TRAIN,
				"T-1",
				"approve_train",
				"Approve Train"
			),
			Row(
				DispatchDecision.ReservePath("T-2", "zA", "InOut-B"),
				DispatchDecisionKind.RESERVE_PATH,
				"T-2",
				"reserve_path",
				"Reserve Path"
			),
			Row(DispatchDecision.NoAction, DispatchDecisionKind.NO_ACTION, null, "no_action", "No Action"),
			Row(
				DispatchDecision.HoldTrain("T-3", 1.0),
				DispatchDecisionKind.HOLD_TRAIN,
				"T-3",
				"hold_train",
				"Hold Train"
			),
			Row(
				DispatchDecision.SetSignalAspect("doA1", Signal.STOP, trainName = "T-4"),
				DispatchDecisionKind.SET_SIGNAL_ASPECT,
				"T-4",
				"set_signal_aspect",
				"Set Signal Aspect"
			),
			Row(
				DispatchDecision.SetSwitchPosition("vA", RailSwitch.Conf.MAIN),
				DispatchDecisionKind.SET_SWITCH_POSITION,
				null,
				"set_switch_position",
				"Set Switch Position"
			),
			Row(
				DispatchDecision.ReleaseRoute("T-5"),
				DispatchDecisionKind.RELEASE_ROUTE,
				"T-5",
				"cancel_route",
				"Release Route"
			),
			Row(
				DispatchDecision.RequestRoute("T-6", "zA", "InOut-B"),
				DispatchDecisionKind.REQUEST_ROUTE,
				"T-6",
				"request_route",
				"Request Route"
			)
		)

	@Test
	fun everySubtypeReportsItsKind() {
		rows.forEach { assertEquals(it.kind, it.decision.kind, "kind of ${it.decision}") }
	}

	@Test
	fun everySubtypeReportsItsTrainName() {
		rows.forEach { assertEquals(it.trainName, it.decision.trainName, "trainName of ${it.decision}") }
	}

	@Test
	fun setSignalAspectWithoutAttributionHasNoTrainName() {
		assertEquals(null, DispatchDecision.SetSignalAspect("doA1", Signal.STOP).trainName)
	}

	@Test
	fun commandTypeMatchesTheToolNames() {
		rows.forEach { assertEquals(it.commandType, it.kind.commandType, "commandType of ${it.kind}") }
	}

	@Test
	fun displayNameMatchesTheApprovalDialogLabels() {
		rows.forEach { assertEquals(it.displayName, it.kind.displayName, "displayName of ${it.kind}") }
	}

	@Test
	fun theTableCoversEveryKindOnce() {
		assertEquals(DispatchDecisionKind.entries.toSet(), rows.map { it.kind }.toSet())
		assertEquals(DispatchDecisionKind.entries.size, rows.size)
	}
}
