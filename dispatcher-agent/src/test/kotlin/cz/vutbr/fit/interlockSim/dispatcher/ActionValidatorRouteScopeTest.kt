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
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.CandidateTarget
import cz.vutbr.fit.interlockSim.sim.SeparatorKind
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Verifies the [RouteScope] discriminant on [DispatchAction.RequestRoute] (Issue #848 / #829).
 *
 * [RuleBasedEmissionStrategy] maps the rule dispatcher's per-block-boundary `ReservePath`
 * decision to a `RequestRoute` carrying [RouteScope.Section] — an intermediate hop, not the
 * train's final destination. [ActionValidator] must **skip** the destination-match check
 * ([RejectionCode.TARGET_NOT_TRAIN_DESTINATION]) for section-scoped requests; only
 * [RouteScope.EndToEnd] requests are checked against the train's declared destination.
 *
 * Without this discriminant the rule-based path-extension pattern (reserve one block
 * boundary at a time toward the destination) would be rejected on every multi-hop route,
 * breaking the P10 determinism gate.
 *
 * @since Issue #829 (SP2c.6 — Goal 10 four-actuator tool surface)
 */
@DisplayName("ActionValidator — RouteScope.Section skips destination check (#848 / #829)")
class ActionValidatorRouteScopeTest {
	private val validator =
		ActionValidator(
			validEndpointNames = setOf("A", "B", "C"),
			blockIds = setOf("kA", "kB")
		)

	/** Train T1 is RUNNING and declared for destination B (in the trains list). */
	private fun observation(): DispatcherObservation =
		DispatcherObservation(
			tick = 1L,
			simTime = 10.0,
			trains =
				listOf(
					TrainView(
						trainId = "T1",
						phase = TrainPhase.RUNNING,
						frontSectionName = null,
						velocityMps = 0.0,
						accelerationMps2 = 0.0,
						destinationInOutName = "B",
						signalAheadName = null,
						signalAheadAspect = null,
						distanceToSignalAheadMetres = 0.0,
						waitingSinceSimTime = null,
						waitSeconds = 0.0
					)
				),
			blocks = emptyList(),
			switches = emptyList(),
			signals = emptyList(),
			reservations = emptyList(),
			queued = emptyList(),
			activeCount = 1,
			capacity = 2,
			appliedOutcomes = emptyList()
		)

	@Nested
	@DisplayName("Section scope: destination check is skipped")
	inner class SectionScope {
		/**
		 * `toEndpointName "C"` differs from the declared destination "B", yet a section-scoped
		 * request is Valid — "C" is an intermediate hop, not the train's final destination.
		 * Would fail if the [RouteScope.Section] branch were dropped from
		 * `validateRequestRouteDestinationAndOrigin`.
		 */
		@Test
		@DisplayName("section-scoped request to a non-destination endpoint is Valid")
		fun sectionScopeSkipsDestinationCheck() {
			val action =
				DispatchAction.RequestRoute(
					trainId = "T1",
					fromEndpointName = "A",
					toEndpointName = "C",
					scope = RouteScope.Section
				)

			val verdict = validator.validate(action, observation())

			assertThat(verdict).isEqualTo(ValidationVerdict.Valid)
		}

		/**
		 * A section-scoped request whose `toEndpointName` happens to equal the destination is also
		 * Valid — matching the destination is allowed, just not required, for a section hop.
		 */
		@Test
		@DisplayName("section-scoped request to the destination endpoint is also Valid")
		fun sectionScopeToDestinationAlsoValid() {
			val action =
				DispatchAction.RequestRoute(
					trainId = "T1",
					fromEndpointName = "A",
					toEndpointName = "B",
					scope = RouteScope.Section
				)

			val verdict = validator.validate(action, observation())

			assertThat(verdict).isEqualTo(ValidationVerdict.Valid)
		}
	}

	/**
	 * The next-section rule (Issue #1378) is a section-hop rule: it checks a [RouteScope.Section]
	 * request against the separators listed one section ahead of its origin, and leaves an
	 * [RouteScope.EndToEnd] request to the destination rule.
	 */
	@Nested
	@DisplayName("next-section rule: Section scope is checked, EndToEnd is skipped (#1378)")
	inner class NextSectionRule {
		/** T1's input facing A lists only B one section ahead. */
		private fun observationWithInputAheadOfA(): DispatcherObservation =
			observation().copy(
				innerBlockInputs =
					listOf(
						BlockInputObservation(
							blockId = "kA",
							towardSemaphoreName = "A",
							state = TrackFacility.State.OCCUPIED,
							ownerTrainId = "T1",
							isApproachingThisInput = true,
							pathSetUpTowardThisInput = false,
							pathAlreadyExtendedBeyond = false,
							candidateTargets = listOf(CandidateTarget("B", SeparatorKind.IN_OUT, available = true))
						)
					)
			)

		@Test
		@DisplayName("a section-scoped request to a target not listed ahead is TARGET_NOT_NEXT_SECTION")
		fun sectionScopeIsChecked() {
			val action = DispatchAction.RequestRoute("T1", "A", "C", RouteScope.Section)

			val verdict = validator.validate(action, observationWithInputAheadOfA())

			assertThat(verdict).isInstanceOf(ValidationVerdict.Rejected::class)
			assertThat((verdict as ValidationVerdict.Rejected).code).isEqualTo(RejectionCode.TARGET_NOT_NEXT_SECTION)
			assertThat(verdict.detail).contains("NEXT SECTION is from 'A' to 'B'")
		}

		@Test
		@DisplayName("a section-scoped request to the listed target is Valid")
		fun sectionScopeListedTargetIsValid() {
			val action = DispatchAction.RequestRoute("T1", "A", "B", RouteScope.Section)

			assertThat(validator.validate(action, observationWithInputAheadOfA())).isEqualTo(ValidationVerdict.Valid)
		}

		@Test
		@DisplayName("an end-to-end request is not checked by the next-section rule")
		fun endToEndIsSkipped() {
			// C is not listed ahead of A, but an EndToEnd request to the declared destination B is the
			// destination rule's business; give the train destination C so that rule passes.
			val observation =
				observationWithInputAheadOfA().let { obs ->
					obs.copy(trains = obs.trains.map { it.copy(destinationInOutName = "C") })
				}
			val action = DispatchAction.RequestRoute("T1", "A", "C", RouteScope.EndToEnd)

			assertThat(validator.validate(action, observation)).isEqualTo(ValidationVerdict.Valid)
		}
	}

	@Nested
	@DisplayName("EndToEnd scope: destination check is enforced (control)")
	inner class EndToEndScope {
		/**
		 * Control case: the same request with the default [RouteScope.EndToEnd] scope is rejected
		 * with [RejectionCode.TARGET_NOT_TRAIN_DESTINATION] — "C" is not the declared destination
		 * "B". Confirms the Section test above is actually exercising the destination check, not
		 * passing for some other reason.
		 */
		@Test
		@DisplayName("end-to-end request to a non-destination endpoint is rejected")
		fun endToEndEnforcesDestinationCheck() {
			val action =
				DispatchAction.RequestRoute(
					trainId = "T1",
					fromEndpointName = "A",
					toEndpointName = "C",
					scope = RouteScope.EndToEnd
				)

			val verdict = validator.validate(action, observation())

			assertThat(verdict).isInstanceOf(ValidationVerdict.Rejected::class)
			assertThat((verdict as ValidationVerdict.Rejected).code)
				.isEqualTo(RejectionCode.TARGET_NOT_TRAIN_DESTINATION)
		}
	}
}
