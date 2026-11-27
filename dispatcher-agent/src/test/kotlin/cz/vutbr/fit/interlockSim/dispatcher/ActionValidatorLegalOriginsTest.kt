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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.ActionValidatorTableTest.Companion.VALIDATOR
import cz.vutbr.fit.interlockSim.dispatcher.ActionValidatorTableTest.Companion.obs
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [ActionValidator]'s origin rule is the shared
 * [cz.vutbr.fit.interlockSim.sim.RouteRequestAdmission.legalOriginsOf] (Issue #1414): it now also
 * closes the not-yet-entered-train hole `RequestRouteTool` closed in PR #1409, and reads "standing"
 * the way the tool does (stopped at a non-blank signal ahead), not only `TrainPhase.HELD`.
 */
@DisplayName("ActionValidator — shared legalOriginsOf origin rule (Issue #1414)")
class ActionValidatorLegalOriginsTest {
	private fun train(
		phase: TrainPhase,
		frontSectionName: String? = "kA",
		signalAheadName: String? = null,
		velocityMps: Double = 0.0,
		originInOutName: String? = "A"
	): TrainView =
		TrainView(
			trainId = "T1",
			phase = phase,
			frontSectionName = frontSectionName,
			velocityMps = velocityMps,
			accelerationMps2 = 0.0,
			destinationInOutName = "B",
			signalAheadName = signalAheadName,
			signalAheadAspect = null,
			distanceToSignalAheadMetres = 0.0,
			waitingSinceSimTime = null,
			waitSeconds = 0.0,
			originInOutName = originInOutName
		)

	private fun verdict(
		train: TrainView,
		from: String,
		to: String = "sigB"
	): ValidationVerdict =
		VALIDATOR.validate(
			DispatchAction.RequestRoute("T1", from, to, RouteScope.Section),
			obs(trains = listOf(train), activeCount = 1)
		)

	private fun ValidationVerdict.code(): RejectionCode? = (this as? ValidationVerdict.Rejected)?.code

	@Test
	fun `an approved train that has not entered must start at its timetable entry`() {
		val verdict = verdict(train(TrainPhase.HELD, frontSectionName = null), from = "C")

		assertThat(verdict).isInstanceOf(ValidationVerdict.Rejected::class)
		assertThat(verdict.code()).isEqualTo(RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION)
		assertThat((verdict as ValidationVerdict.Rejected).detail).contains("'A'")
	}

	@Test
	fun `an approved train that has not entered may start at its timetable entry`() {
		val verdict = verdict(train(TrainPhase.HELD, frontSectionName = null), from = "A")

		assertThat(verdict.code()).isNotEqualTo(RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION)
	}

	@Test
	fun `a dwelling train standing at a signal must start there`() {
		val verdict = verdict(train(TrainPhase.DWELLING, signalAheadName = "sigA"), from = "sigB", to = "B")

		assertThat(verdict.code()).isEqualTo(RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION)
		assertThat((verdict as ValidationVerdict.Rejected).detail).contains("'sigA'")
	}

	@Test
	fun `a moving train is not held to its signal ahead`() {
		val verdict =
			verdict(train(TrainPhase.RUNNING, signalAheadName = "sigA", velocityMps = 5.0), from = "sigB", to = "B")

		assertThat(verdict.code()).isNotEqualTo(RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION)
	}
}
