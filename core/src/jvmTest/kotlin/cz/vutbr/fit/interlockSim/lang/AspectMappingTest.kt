package cz.vutbr.fit.interlockSim.lang

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the [Signal] ↔ [Aspect] premapping (SP3.3, Issue #571).
 *
 * Verifies that every legacy [Signal] maps to a defined [Aspect] (total mapping), that the
 * mapped aspects round-trip back to the original [Signal], and that the distant / calling-on /
 * shunting aspects (which have no [Signal] equivalent) map to `null`.
 */
class AspectMappingTest {
	@Nested
	inner class SignalToAspect {
		@Test
		fun stopMapsToStuj() {
			assertThat(Signal.STOP.toAspect()).isEqualTo(Aspect.Stuj)
		}

		@Test
		fun freeMapsToVolno() {
			assertThat(Signal.FREE.toAspect()).isEqualTo(Aspect.Volno)
		}

		@Test
		fun speedSignalsMapToRychlost() {
			assertThat(Signal.S30.toAspect()).isEqualTo(Aspect.Rychlost(30))
			assertThat(Signal.S40.toAspect()).isEqualTo(Aspect.Rychlost(40))
			assertThat(Signal.S60.toAspect()).isEqualTo(Aspect.Rychlost(60))
			assertThat(Signal.S80.toAspect()).isEqualTo(Aspect.Rychlost(80))
			assertThat(Signal.S100.toAspect()).isEqualTo(Aspect.Rychlost(100))
		}

		@Test
		fun everySignalHasAnAspect() {
			// Total mapping: no Signal is left unmapped. Exhaustive — if a new Signal value is
			// added without a mapping arm, toAspect() fails to compile (no `else` branch).
			Signal.values().forEach { signal ->
				signal.toAspect()
			}
		}
	}

	@Nested
	inner class AspectToSignal {
		@Test
		fun stujMapsToStop() {
			assertThat(Aspect.Stuj.toSignal()).isEqualTo(Signal.STOP)
		}

		@Test
		fun volnoMapsToFree() {
			assertThat(Aspect.Volno.toSignal()).isEqualTo(Signal.FREE)
		}

		@Test
		fun rychlostRoundTripsForModelledSpeeds() {
			assertThat(Aspect.Rychlost(30).toSignal()).isEqualTo(Signal.S30)
			assertThat(Aspect.Rychlost(40).toSignal()).isEqualTo(Signal.S40)
			assertThat(Aspect.Rychlost(60).toSignal()).isEqualTo(Signal.S60)
			assertThat(Aspect.Rychlost(80).toSignal()).isEqualTo(Signal.S80)
			assertThat(Aspect.Rychlost(100).toSignal()).isEqualTo(Signal.S100)
		}

		@Test
		fun rychlostUnmodelledSpeedMapsToNull() {
			assertThat(Aspect.Rychlost(50).toSignal()).isNull()
		}

		@Test
		fun distantCallingAndShuntingAspectsMapToNull() {
			assertThat(Aspect.Vystraha.toSignal()).isNull()
			assertThat(Aspect.Ocekavejte(50).toSignal()).isNull()
			assertThat(Aspect.PrivolavaciNavest.toSignal()).isNull()
			assertThat(Aspect.PosunDovolen.toSignal()).isNull()
			assertThat(Aspect.PosunZakazan.toSignal()).isNull()
		}
	}

	@Nested
	inner class RoundTrip {
		@Test
		fun signalToAspectAndBackForModelledValues() {
			listOf(Signal.STOP, Signal.S30, Signal.S40, Signal.S60, Signal.S80, Signal.S100, Signal.FREE).forEach { signal ->
				assertThat(signal.toAspect().toSignal()).isEqualTo(signal)
			}
		}

		@Test
		fun s30RoundTripsThroughRychlost30() {
			assertThat(Signal.S30.toAspect()).isEqualTo(Aspect.Rychlost(30))
			assertThat(Aspect.Rychlost(30).toSignal()).isEqualTo(Signal.S30)
		}
	}
}
