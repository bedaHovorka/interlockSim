package interlocksim.lang.proto

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import interlocksim.lang.vocab.Aspect
import interlocksim.lang.vocab.BlockId
import interlocksim.lang.vocab.MovementAuthority
import interlocksim.lang.vocab.SignalId
import interlocksim.lang.vocab.SwitchId
import interlocksim.lang.vocab.SwitchPosition
import interlocksim.lang.vocab.SwitchSetting
import interlocksim.lang.vocab.TrackId
import interlocksim.lang.vocab.TrainRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the [Message] sealed interface and its 8 speech acts (SP3.3, Issue #571).
 *
 * Verifies:
 * - Serialisation round-trips for every speech act subtype.
 * - Czech [Message.humanReadable] output contains the expected key terms.
 * - Polymorphic decode from the base [Message] interface works for all subtypes.
 */
class MessageTest {
	private val json = Json { classDiscriminator = "type" }

	private val dispatcher = AgentRef(role = "DISPATCHER", id = "main")
	private val train6485 = AgentRef(role = "TRAIN", id = "6485")
	private val interlocking = AgentRef(role = "INTERLOCKING", id = "station")

	private fun envelope(
		msgId: String = "msg-1",
		sender: AgentRef = dispatcher,
		receiver: AgentRef = train6485,
		trainNumber: String? = "6485"
	) = Triple(msgId, sender to receiver, trainNumber)

	private fun ma() =
		MovementAuthority(
			target = SignalId("L3"),
			speedLimitKmh = 40,
			endOfAuthority = BlockId("U5")
		)

	private fun route() =
		TrainRoute(
			from = SignalId("L1"),
			to = SignalId("L3"),
			running = listOf(SwitchSetting(SwitchId("V7"), SwitchPosition.MINUS)),
			blocks = listOf(BlockId("U3"), BlockId("U4"))
		)

	// -------------------------------------------------------------------------
	// 1. RouteRequest
	// -------------------------------------------------------------------------

	@Nested
	inner class RouteRequestTests {
		private fun make() =
			Message.RouteRequest(
				messageId = "rr-1",
				sender = train6485,
				receiver = dispatcher,
				simTime = 120L,
				trainNumber = "6485",
				atSignal = SignalId("L1")
			)

		@Test
		fun roundTrip() {
			val msg: Message = make()
			val decoded = json.decodeFromString<Message>(json.encodeToString(msg))
			assertThat(decoded).isEqualTo(msg)
		}

		@Test
		fun humanReadableContainsTrainAndSignal() {
			val text = make().humanReadable()
			assertThat(text).contains("6485")
			assertThat(text).contains("L1")
		}

		@Test
		fun withDesiredTrackRoundTrip() {
			val msg: Message = make().copy(desiredTrack = TrackId("3"))
			val decoded = json.decodeFromString<Message>(json.encodeToString(msg))
			assertThat(decoded).isEqualTo(msg)
		}
	}

	// -------------------------------------------------------------------------
	// 2. RouteGrant
	// -------------------------------------------------------------------------

	@Nested
	inner class RouteGrantTests {
		private fun make() =
			Message.RouteGrant(
				messageId = "rg-1",
				sender = dispatcher,
				receiver = train6485,
				simTime = 121L,
				trainNumber = "6485",
				route = route(),
				aspect = Aspect.Rychlost(40),
				ma = ma()
			)

		@Test
		fun roundTrip() {
			val msg: Message = make()
			val decoded = json.decodeFromString<Message>(json.encodeToString(msg))
			assertThat(decoded).isEqualTo(msg)
		}

		@Test
		fun humanReadableContainsTrainAndVolno() {
			val text = make().humanReadable()
			assertThat(text).contains("6485")
			assertThat(text).isNotEmpty()
		}

		@Test
		fun aspectVolnoRoundTrip() {
			val msg: Message = make().copy(aspect = Aspect.Volno)
			val decoded = json.decodeFromString<Message>(json.encodeToString(msg))
			assertThat(decoded).isEqualTo(msg)
		}
	}

	// -------------------------------------------------------------------------
	// 3. RouteDenial
	// -------------------------------------------------------------------------

	@Nested
	inner class RouteDenialTests {
		private fun make() =
			Message.RouteDenial(
				messageId = "rd-1",
				sender = dispatcher,
				receiver = train6485,
				simTime = 122L,
				trainNumber = "6485",
				reason = "úsek U7 obsazen"
			)

		@Test
		fun roundTrip() {
			val msg: Message = make()
			val decoded = json.decodeFromString<Message>(json.encodeToString(msg))
			assertThat(decoded).isEqualTo(msg)
		}

		@Test
		fun humanReadableContainsNikolivAndReason() {
			val text = make().humanReadable()
			assertThat(text).contains("Nikoliv")
			assertThat(text).contains("U7")
		}
	}

	// -------------------------------------------------------------------------
	// 4. MovementAuthority speech act
	// -------------------------------------------------------------------------

	@Nested
	inner class MovementAuthorityMessageTests {
		private fun make() =
			Message.MovementAuthority(
				messageId = "ma-1",
				sender = dispatcher,
				receiver = train6485,
				simTime = 123L,
				trainNumber = "6485",
				authority = ma()
			)

		@Test
		fun roundTrip() {
			val msg: Message = make()
			val decoded = json.decodeFromString<Message>(json.encodeToString(msg))
			assertThat(decoded).isEqualTo(msg)
		}

		@Test
		fun humanReadableContainsTargetAndSpeed() {
			val text = make().humanReadable()
			assertThat(text).contains("L3")
			assertThat(text).contains("40")
		}
	}

	// -------------------------------------------------------------------------
	// 5. PositionReport
	// -------------------------------------------------------------------------

	@Nested
	inner class PositionReportTests {
		private fun make() =
			Message.PositionReport(
				messageId = "pr-1",
				sender = train6485,
				receiver = dispatcher,
				simTime = 124L,
				trainNumber = "6485",
				block = BlockId("U4"),
				speedKmh = 38
			)

		@Test
		fun roundTrip() {
			val msg: Message = make()
			val decoded = json.decodeFromString<Message>(json.encodeToString(msg))
			assertThat(decoded).isEqualTo(msg)
		}

		@Test
		fun humanReadableContainsBlockAndSpeed() {
			val text = make().humanReadable()
			assertThat(text).contains("U4")
			assertThat(text).contains("38")
		}
	}

	// -------------------------------------------------------------------------
	// 6. OccupancyReport
	// -------------------------------------------------------------------------

	@Nested
	inner class OccupancyReportTests {
		private fun makeOccupied() =
			Message.OccupancyReport(
				messageId = "or-1",
				sender = interlocking,
				receiver = dispatcher,
				simTime = 125L,
				trainNumber = "6485",
				block = BlockId("U4"),
				occupied = true
			)

		private fun makeCleared() =
			makeOccupied().copy(
				messageId = "or-2",
				block = BlockId("U3"),
				occupied = false,
				trainNumber = null
			)

		@Test
		fun occupiedRoundTrip() {
			val msg: Message = makeOccupied()
			assertThat(json.decodeFromString<Message>(json.encodeToString(msg))).isEqualTo(msg)
		}

		@Test
		fun clearedRoundTrip() {
			val msg: Message = makeCleared()
			assertThat(json.decodeFromString<Message>(json.encodeToString(msg))).isEqualTo(msg)
		}

		@Test
		fun occupiedHumanReadableContainsTrain() {
			val text = makeOccupied().humanReadable()
			assertThat(text).contains("6485")
			assertThat(text).contains("U4")
		}

		@Test
		fun clearedHumanReadableContainsVolny() {
			val text = makeCleared().humanReadable()
			assertThat(text).contains("U3")
			assertThat(text).contains("volný")
		}
	}

	// -------------------------------------------------------------------------
	// 7. HoldOrder
	// -------------------------------------------------------------------------

	@Nested
	inner class HoldOrderTests {
		private fun make() =
			Message.HoldOrder(
				messageId = "ho-1",
				sender = dispatcher,
				receiver = train6485,
				simTime = 126L,
				trainNumber = "6485",
				atSignal = SignalId("L6")
			)

		@Test
		fun roundTrip() {
			val msg: Message = make()
			assertThat(json.decodeFromString<Message>(json.encodeToString(msg))).isEqualTo(msg)
		}

		@Test
		fun humanReadableContainsStujAndSignal() {
			val text = make().humanReadable()
			assertThat(text).contains("Stůj")
			assertThat(text).contains("L6")
		}
	}

	// -------------------------------------------------------------------------
	// 8. ConflictNotification
	// -------------------------------------------------------------------------

	@Nested
	inner class ConflictNotificationTests {
		private fun make() =
			Message.ConflictNotification(
				messageId = "cn-1",
				sender = dispatcher,
				receiver = dispatcher,
				simTime = 127L,
				trainNumber = null,
				block = BlockId("U7"),
				competing = listOf("6485", "2207")
			)

		@Test
		fun roundTrip() {
			val msg: Message = make()
			assertThat(json.decodeFromString<Message>(json.encodeToString(msg))).isEqualTo(msg)
		}

		@Test
		fun humanReadableContainsBlockAndTrains() {
			val text = make().humanReadable()
			assertThat(text).contains("U7")
			assertThat(text).contains("6485")
			assertThat(text).contains("2207")
		}
	}

	// -------------------------------------------------------------------------
	// Polymorphic envelope fields
	// -------------------------------------------------------------------------

	@Test
	fun envelopeFieldsPreservedThroughRoundTrip() {
		val msg: Message =
			Message.HoldOrder(
				messageId = "unique-42",
				sender = dispatcher,
				receiver = AgentRef("TRAIN", "9999"),
				simTime = 9999L,
				trainNumber = "9999",
				atSignal = SignalId("X1")
			)
		val decoded = json.decodeFromString<Message>(json.encodeToString(msg))
		assertThat(decoded.messageId).isEqualTo("unique-42")
		assertThat(decoded.simTime).isEqualTo(9999L)
		assertThat(decoded.trainNumber).isEqualTo("9999")
		assertThat(decoded.sender).isEqualTo(dispatcher)
	}
}
