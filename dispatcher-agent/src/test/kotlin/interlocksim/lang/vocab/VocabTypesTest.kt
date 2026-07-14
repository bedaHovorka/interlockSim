package interlocksim.lang.vocab

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Serialisation round-trip tests for the vocab value and composite types (SP3.2, Issue #570).
 *
 * Covers: [SwitchPosition], identifier value classes ([SignalId], [SwitchId], [BlockId],
 * [TrackId]), [SwitchSetting], [TrainRoute], [MovementAuthority].
 */
class VocabTypesTest {
	private val json = Json

	// -------------------------------------------------------------------------
	// SwitchPosition
	// -------------------------------------------------------------------------

	@Nested
	inner class SwitchPositionTests {
		@Test
		fun plusRoundTrip() {
			val encoded = json.encodeToString(SwitchPosition.PLUS)
			assertThat(json.decodeFromString<SwitchPosition>(encoded)).isEqualTo(SwitchPosition.PLUS)
		}

		@Test
		fun minusRoundTrip() {
			val encoded = json.encodeToString(SwitchPosition.MINUS)
			assertThat(json.decodeFromString<SwitchPosition>(encoded)).isEqualTo(SwitchPosition.MINUS)
		}

		@Test
		fun plusSerialName() {
			assertThat(json.encodeToString(SwitchPosition.PLUS)).isEqualTo(""""plus"""")
		}

		@Test
		fun minusSerialName() {
			assertThat(json.encodeToString(SwitchPosition.MINUS)).isEqualTo(""""minus"""")
		}
	}

	// -------------------------------------------------------------------------
	// Identifier value classes
	// -------------------------------------------------------------------------

	@Nested
	inner class IdentifierTests {
		@Test
		fun signalIdPreservesName() {
			val id = SignalId("L1")
			assertThat(id.name).isEqualTo("L1")
		}

		@Test
		fun signalIdRoundTrip() {
			val id = SignalId("S2")
			val decoded = json.decodeFromString<SignalId>(json.encodeToString(id))
			assertThat(decoded).isEqualTo(id)
		}

		@Test
		fun signalIdSerializesAsScalar() {
			assertThat(json.encodeToString(SignalId("L1"))).isEqualTo(""""L1"""")
		}

		@Test
		fun switchIdRoundTrip() {
			val id = SwitchId("V7")
			assertThat(json.decodeFromString<SwitchId>(json.encodeToString(id))).isEqualTo(id)
		}

		@Test
		fun blockIdRoundTrip() {
			val id = BlockId("U3")
			assertThat(json.decodeFromString<BlockId>(json.encodeToString(id))).isEqualTo(id)
		}

		@Test
		fun trackIdRoundTrip() {
			val id = TrackId("3")
			assertThat(json.decodeFromString<TrackId>(json.encodeToString(id))).isEqualTo(id)
		}
	}

	// -------------------------------------------------------------------------
	// SwitchSetting
	// -------------------------------------------------------------------------

	@Nested
	inner class SwitchSettingTests {
		@Test
		fun roundTrip() {
			val setting = SwitchSetting(SwitchId("V7"), SwitchPosition.MINUS)
			val decoded = json.decodeFromString<SwitchSetting>(json.encodeToString(setting))
			assertThat(decoded).isEqualTo(setting)
		}
	}

	// -------------------------------------------------------------------------
	// TrainRoute
	// -------------------------------------------------------------------------

	@Nested
	inner class TrainRouteTests {
		@Test
		fun roundTripMinimal() {
			val route =
				TrainRoute(
					from = SignalId("L1"),
					to = SignalId("L3"),
					running = listOf(SwitchSetting(SwitchId("V7"), SwitchPosition.MINUS)),
					blocks = listOf(BlockId("U3"), BlockId("U4"))
				)
			val decoded = json.decodeFromString<TrainRoute>(json.encodeToString(route))
			assertThat(decoded).isEqualTo(route)
		}

		@Test
		fun roundTripWithFlankAndTrack() {
			val route =
				TrainRoute(
					from = SignalId("L1"),
					to = SignalId("L3"),
					running = listOf(SwitchSetting(SwitchId("V7"), SwitchPosition.MINUS)),
					flank = listOf(SwitchSetting(SwitchId("V9"), SwitchPosition.PLUS)),
					track = TrackId("3"),
					blocks = listOf(BlockId("U3"), BlockId("U4"), BlockId("U5"))
				)
			val decoded = json.decodeFromString<TrainRoute>(json.encodeToString(route))
			assertThat(decoded).isEqualTo(route)
		}
	}

	// -------------------------------------------------------------------------
	// MovementAuthority
	// -------------------------------------------------------------------------

	@Nested
	inner class MovementAuthorityTests {
		@Test
		fun roundTrip() {
			val ma =
				MovementAuthority(
					target = SignalId("L3"),
					speedLimitKmh = 40,
					endOfAuthority = BlockId("U5")
				)
			val decoded = json.decodeFromString<MovementAuthority>(json.encodeToString(ma))
			assertThat(decoded).isEqualTo(ma)
		}
	}
}
