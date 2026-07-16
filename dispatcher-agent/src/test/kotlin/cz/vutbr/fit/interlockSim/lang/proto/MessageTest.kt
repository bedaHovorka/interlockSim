package cz.vutbr.fit.interlockSim.lang.proto

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.lang.LangSerialization
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.MovementAuthority
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchPosition
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchSetting
import cz.vutbr.fit.interlockSim.lang.vocab.TrackId
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the [Message] sealed interface and its 8 speech acts (SP3.3, Issue #571).
 *
 * Verifies:
 * - Serialisation round-trips for every speech act subtype.
 * - Czech [Message.humanReadable] output contains the expected key terms.
 * - Polymorphic decode from the base [Message] interface works for all subtypes.
 * - Schema stability: the stable `@SerialName` discriminators and the `"type"` class
 *   discriminator are pinned by exact-substring assertions, so a silent rename fails the
 *   suite rather than only passing round-trip.
 */
class MessageTest {
	private val json = LangSerialization.json

	private val dispatcher = AgentRef(role = AgentRole.DISPATCHER, id = "main")
	private val train6485 = AgentRef(role = AgentRole.TRAIN, id = "6485")
	private val interlocking = AgentRef(role = AgentRole.INTERLOCKING, id = "station")

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

		@Test
		fun humanReadableWithNullTrainNumberUsesPlaceholder() {
			val text = make().copy(trainNumber = null).humanReadable()
			assertThat(text).contains("(neznámý vlak)")
			assertThat(text).doesNotContain("null")
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
		fun humanReadableContainsTrainAndAspect() {
			val text = make().humanReadable()
			assertThat(text).contains("6485")
			assertThat(text).contains("Rychlost 40 km/h")
		}

		@Test
		fun humanReadableContainsVolnoAspect() {
			val text = make().copy(aspect = Aspect.Volno).humanReadable()
			assertThat(text).contains("Volno")
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
		fun humanReadableUsesGenitiveVlakuAndContainsTargetAndSpeed() {
			val text = make().humanReadable()
			// I-1: "rozkaz k odjezdu vlaku" (genitive), not "vlak".
			assertThat(text).contains("Rozkaz k odjezdu vlaku")
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

		@Test
		fun occupiedWithNullTrainNumberUsesPlaceholder() {
			val text = makeOccupied().copy(trainNumber = null).humanReadable()
			assertThat(text).contains("(neznámý vlak)")
			assertThat(text).doesNotContain("null")
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

		@Test
		fun rejectsEmptyCompetingList() {
			// I-6: a conflict with zero competing trains is meaningless — rejected at construction.
			assertThrows<IllegalArgumentException> {
				Message.ConflictNotification(
					messageId = "cn-x",
					sender = dispatcher,
					receiver = dispatcher,
					simTime = 127L,
					trainNumber = null,
					block = BlockId("U7"),
					competing = emptyList()
				)
			}
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
				receiver = AgentRef(AgentRole.TRAIN, "9999"),
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

	// -------------------------------------------------------------------------
	// Schema stability (Rec#1) — pins the @SerialName discriminators + class discriminator
	// -------------------------------------------------------------------------

	@Nested
	inner class SchemaStability {
		private fun encoded(msg: Message) = json.encodeToString(msg)

		@Test
		fun everySpeechActCarriesItsStableDiscriminator() {
			// Each @SerialName is the protocol contract — it must not silently change. A round-trip
			// alone would not catch a rename (encode and decode use the same name), so pin the
			// discriminator substring on the wire for all 8 acts.
			assertAll {
				assertThat(encoded(Message.RouteRequest("rr", train6485, dispatcher, 0L, "6485", SignalId("L1"))))
					.contains(""""type":"route_request"""")
				assertThat(encoded(Message.RouteGrant("rg", dispatcher, train6485, 0L, "6485", route(), Aspect.Volno, ma())))
					.contains(""""type":"route_grant"""")
				assertThat(encoded(Message.RouteDenial("rd", dispatcher, train6485, 0L, "6485", "obsazeno")))
					.contains(""""type":"route_denial"""")
				assertThat(encoded(Message.MovementAuthority("ma", dispatcher, train6485, 0L, "6485", ma())))
					.contains(""""type":"movement_authority"""")
				assertThat(encoded(Message.PositionReport("pr", train6485, dispatcher, 0L, "6485", BlockId("U4"), 38)))
					.contains(""""type":"position_report"""")
				assertThat(encoded(Message.OccupancyReport("or", interlocking, dispatcher, 0L, "6485", BlockId("U4"), true)))
					.contains(""""type":"occupancy_report"""")
				assertThat(encoded(Message.HoldOrder("ho", dispatcher, train6485, 0L, "6485", SignalId("L6"))))
					.contains(""""type":"hold_order"""")
				assertThat(
					encoded(Message.ConflictNotification("cn", dispatcher, dispatcher, 0L, null, BlockId("U7"), listOf("6485")))
				).contains(""""type":"conflict_notification"""")
			}
		}

		@Test
		fun classDiscriminatorIsType() {
			// The sealed Message envelope uses "type" as its polymorphic discriminator.
			val wire = encoded(Message.HoldOrder("ho", dispatcher, train6485, 0L, "6485", SignalId("L6")))
			assertThat(wire).contains(""""type":"""")
		}

		@Test
		fun discriminatorDecodesBackToCorrectSubtype() {
			// Hardcoded wire JSON round-trips through the base Message interface to the right
			// subtype. Newlines between tokens are insignificant JSON whitespace, so the wire is
			// split across lines for readability without changing what the parser sees.
			val wire =
				"""
				{"type":"route_grant","messageId":"rg",
				 "sender":{"role":"dispatcher","id":"main"},
				 "receiver":{"role":"train","id":"6485"},
				 "simTime":0,"trainNumber":"6485",
				 "route":{"from":"L1","to":"L3","running":[{"switch":"V7","position":"minus"}],"blocks":["U3","U4"]},
				 "aspect":{"type":"volno"},
				 "ma":{"target":"L3","speedLimitKmh":40,"endOfAuthority":"U5"}}
				""".trimIndent()
			val decoded = json.decodeFromString<Message>(wire)
			assertThat(decoded).isInstanceOf<Message.RouteGrant>()
		}

		@Test
		fun agentRoleSerializesAsLowercaseDiscriminator() {
			// M-6: AgentRole uses lowercase @SerialName matching the protocol convention.
			assertThat(json.encodeToString(dispatcher)).contains(""""role":"dispatcher"""")
			assertThat(json.encodeToString(train6485)).contains(""""role":"train"""")
			assertThat(json.encodeToString(interlocking)).contains(""""role":"interlocking"""")
		}
	}
}
