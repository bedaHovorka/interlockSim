/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for RouteRequestAdmission.legalOriginsOf (Issue #1414).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TimetableReading
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.sim.RouteRequestAdmission.LegalOrigins
import cz.vutbr.fit.interlockSim.sim.RouteRequestAdmission.OriginFacts
import kotlin.test.Test

/**
 * Unit tests for [RouteRequestAdmission.legalOriginsOf] — the one origin rule `RequestRouteTool`
 * and `ActionValidator` share (Issue #1414) — and for [standingAtSignalName], the "standing at
 * signal" predicate it reads.
 *
 * Pure common-platform tests (KMP `commonTest`): no mocks, no JUnit 5.
 */
class RouteRequestAdmissionTest {
	private companion object {
		const val TRAIN = "Train #1"
		val IN_OUTS: Set<String> = setOf("A", "B")
	}

	private fun perception(
		frontSectionName: String? = "k1",
		signalAheadName: String? = "doB1",
		velocity: Double = 0.0
	): TrainPerceptionReading =
		TrainPerceptionReading(
			trainId = TRAIN,
			signalAheadName = signalAheadName,
			signalAheadAspect = Signal.STOP,
			distanceToSignalAheadMetres = 10.0,
			currentSpeedLimitMps = 10.0,
			velocity = velocity,
			acceleration = 0.0,
			totalDistance = 0.0,
			frontSectionName = frontSectionName,
			destinationInOutName = "B",
			scheduledArrivalTime = 0.0,
			isDwelling = velocity == 0.0
		)

	private fun snapshot(
		perception: TrainPerceptionReading? = null,
		origin: String? = null
	): SimulationSnapshot =
		SimulationSnapshot(
			simTime = 1.0,
			semaphores = emptyList(),
			blocks = emptyList(),
			trainPositions = emptyList(),
			timetables = listOfNotNull(origin?.let { TimetableReading(TRAIN, it, "B", 0.0, 0.0) }),
			trainPerceptions = listOfNotNull(perception)
		)

	private fun legal(
		snapshot: SimulationSnapshot,
		isQueued: Boolean = false,
		inOutNames: Set<String> = IN_OUTS
	): LegalOrigins? = RouteRequestAdmission.legalOriginsOf(snapshot, TRAIN, isQueued, inOutNames)

	// ── queued train ────────────────────────────────────────────────────────

	@Test
	fun `a queued train may start only at an InOut`() {
		val rule = legal(snapshot(), isQueued = true)

		assertThat(rule).isEqualTo(LegalOrigins.AtInOut(IN_OUTS))
		assertThat(rule!!.accepts("A")).isTrue()
		assertThat(rule.accepts("doB1")).isFalse()
	}

	@Test
	fun `a queued train has no origin rule without InOut names`() {
		assertThat(legal(snapshot(), isQueued = true, inOutNames = emptySet())).isNull()
	}

	@Test
	fun `a train neither queued nor active has no origin rule`() {
		assertThat(legal(snapshot(), isQueued = false)).isNull()
	}

	@Test
	fun `a queued train that is already active is judged by its perception`() {
		val rule = legal(snapshot(perception(signalAheadName = "doA1")), isQueued = true)

		assertThat(rule).isEqualTo(LegalOrigins.AtSignal("doA1"))
	}

	// ── approved, not yet entered ───────────────────────────────────────────

	@Test
	fun `an approved train without a front section starts at its timetable origin`() {
		val rule = legal(snapshot(perception(frontSectionName = null, signalAheadName = null), origin = "A"))

		assertThat(rule).isEqualTo(LegalOrigins.AtEntry("A", null))
		assertThat(rule!!.names).isEqualTo(setOf("A"))
	}

	@Test
	fun `a not-yet-entered train whose entry route reaches a signal starts at that signal`() {
		val rule = legal(snapshot(perception(frontSectionName = null, signalAheadName = "doA1"), origin = "A"))

		// The entry route already reaches doA1 and the train stands still, so the standing rule
		// narrows the origin to the signal (RequestRouteTool applied both checks in sequence).
		assertThat(rule).isEqualTo(LegalOrigins.AtSignal("doA1", entryInOut = "A"))
		assertThat(rule!!.accepts("doA1")).isTrue()
		assertThat(rule.accepts("A")).isFalse()
	}

	@Test
	fun `a moving not-yet-entered train may start at its entry or at its signal ahead`() {
		val rule =
			legal(
				snapshot(perception(frontSectionName = null, signalAheadName = "doA1", velocity = 2.0), origin = "A")
			)

		assertThat(rule).isEqualTo(LegalOrigins.AtEntry("A", "doA1"))
		assertThat(rule!!.names).isEqualTo(setOf("A", "doA1"))
	}

	@Test
	fun `a not-yet-entered train without a timetable falls back to the standing rule`() {
		val rule = legal(snapshot(perception(frontSectionName = null, signalAheadName = "doA1"), origin = null))

		assertThat(rule).isEqualTo(LegalOrigins.AtSignal("doA1"))
	}

	@Test
	fun `a blank timetable origin counts as no timetable`() {
		val rule = legal(snapshot(perception(frontSectionName = null, signalAheadName = null), origin = " "))

		assertThat(rule).isNull()
	}

	// ── entered train ───────────────────────────────────────────────────────

	@Test
	fun `a train standing at a signal starts at that signal`() {
		val rule = legal(snapshot(perception(signalAheadName = "doB1"), origin = "A"))

		assertThat(rule).isEqualTo(LegalOrigins.AtSignal("doB1"))
	}

	@Test
	fun `a running train has no origin rule`() {
		// Neither layer constrains a moving train's origin: its signal ahead changes under the
		// dispatcher's feet, and a look-ahead extension starts at the held route's end, which need not
		// be the signal ahead. The kernel's contiguity guard owns that case.
		assertThat(legal(snapshot(perception(signalAheadName = "doB1", velocity = 3.0), origin = "A"))).isNull()
	}

	@Test
	fun `a stopped train with a blank signal ahead has no origin rule`() {
		assertThat(legal(snapshot(perception(signalAheadName = ""), origin = "A"))).isNull()
	}

	// ── facts overload and the standing predicate ───────────────────────────

	@Test
	fun `the facts overload answers like the snapshot overload`() {
		val facts =
			OriginFacts(
				isQueued = false,
				isActive = true,
				frontSectionName = null,
				timetableOriginName = "A",
				signalAheadName = null,
				velocity = 0.0
			)

		assertThat(RouteRequestAdmission.legalOriginsOf(facts, IN_OUTS)).isEqualTo(LegalOrigins.AtEntry("A", null))
	}

	@Test
	fun `standingAtSignalName names the signal ahead of a stopped train only`() {
		assertThat(perception(signalAheadName = "doB1").standingAtSignalName()).isEqualTo("doB1")
		assertThat(perception(signalAheadName = "doB1", velocity = 0.5).standingAtSignalName()).isNull()
		assertThat(perception(signalAheadName = null).standingAtSignalName()).isNull()
		assertThat(perception(signalAheadName = " ").standingAtSignalName()).isNull()
	}
}
