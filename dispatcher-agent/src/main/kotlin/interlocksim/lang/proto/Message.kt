package interlocksim.lang.proto

import ai.koog.agents.core.tools.annotations.LLMDescription
import interlocksim.lang.vocab.Aspect
import interlocksim.lang.vocab.BlockId
import interlocksim.lang.vocab.SignalId
import interlocksim.lang.vocab.TrackId
import interlocksim.lang.vocab.TrainRoute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import interlocksim.lang.vocab.MovementAuthority as VocabMovementAuthority

/**
 * Common inter-agent message envelope with 8 starter speech acts (SP3.3, Issue #571).
 *
 * Every message shares a fixed envelope (messageId, sender, receiver, simTime,
 * trainNumber) and carries exactly one speech act as a nested sealed subtype.
 *
 * ## Speech acts
 *
 * The 8 starter speech acts map onto real Czech dispatcher↔driver/signalman
 * communication acts defined in the SŽDC/SŽ D1/D2 predpisy and the AŽD ESA 11
 * operating vocabulary:
 *
 * | Speech act            | Direction               | Czech act                              |
 * |-----------------------|-------------------------|----------------------------------------|
 * | [RouteRequest]        | Train → Dispatcher      | žádost o vlakovou cestu               |
 * | [RouteGrant]          | Dispatcher → Train      | "postaveno a volno"                    |
 * | [RouteDenial]         | Dispatcher → Train      | "Nikoliv, čekejte."                    |
 * | [MovementAuthority]   | Dispatcher → Train      | oprávnění k jízdě / rozkaz k odjezdu  |
 * | [PositionReport]      | Train → Dispatcher      | poloha vlaku                          |
 * | [OccupancyReport]     | Interlocking → Dispatcher | odhláška / kolejový obvod indikace   |
 * | [HoldOrder]           | Dispatcher → Train      | Stůj / mimořádné zastavení            |
 * | [ConflictNotification]| Dispatcher → Dispatcher | nabídka/přijetí, traťový souhlas      |
 *
 * ## Grammar (§4 of #533)
 *
 * ```
 * Message   ::= Envelope SpeechAct
 * Envelope  ::= messageId sender receiver simTime [trainNumber]
 * SpeechAct ::= RouteRequest | RouteGrant | RouteDenial | MovementAuthority
 *             | PositionReport | OccupancyReport | HoldOrder | ConflictNotification
 * ```
 *
 * ## Serialisation
 *
 * All subtypes are `@Serializable`. The `type` discriminator field in JSON uses
 * the stable `@SerialName` values defined on each subtype.
 *
 * @since Issue #571 (SP3.3 — Goal 10)
 */
@Serializable
@LLMDescription("Inter-agent message envelope with a typed speech act payload.")
sealed interface Message {
	/** Unique message identifier (UUID or counter string). */
	val messageId: String

	/** Agent that sent the message. */
	val sender: AgentRef

	/** Agent that should receive the message. */
	val receiver: AgentRef

	/** Simulation time (kDisco tick) at which the message was created. */
	val simTime: Long

	/** Train number this message concerns, or null for system-level messages. */
	val trainNumber: String?

	/**
	 * Czech human-readable summary of this message.
	 *
	 * Suitable for log output, UI display, and LLM context injection.
	 * Phrased in the style of real Czech dispatcher↔driver communication.
	 */
	fun humanReadable(): String

	// -------------------------------------------------------------------------
	// 1. RouteRequest — Train → Dispatcher
	// -------------------------------------------------------------------------

	/**
	 * Train requests a route from [atSignal] toward [desiredTrack].
	 *
	 * Sent when the train senses a restrictive aspect at [atSignal] and needs
	 * the dispatcher to set and lock a route before it can proceed.
	 *
	 * Real-world analogue: "žádost o vlakovou cestu" (D1/D2).
	 *
	 * @property atSignal     The signal at which the train is stopped or slowing.
	 * @property desiredTrack The target track the train wants to reach, if known.
	 */
	@Serializable
	@SerialName("route_request")
	@LLMDescription("Train requests a route from the dispatcher (žádost o vlakovou cestu).")
	data class RouteRequest(
		override val messageId: String,
		override val sender: AgentRef,
		override val receiver: AgentRef,
		override val simTime: Long,
		override val trainNumber: String?,
		@LLMDescription("Signal at which the train is waiting or slowing.")
		val atSignal: SignalId,
		@LLMDescription("Target track the train wants to reach, if known.")
		val desiredTrack: TrackId? = null
	) : Message {
		override fun humanReadable(): String = "Vlak $trainNumber žádá vlakovou cestu u návěstidla ${atSignal.name}."
	}

	// -------------------------------------------------------------------------
	// 2. RouteGrant — Dispatcher → Train
	// -------------------------------------------------------------------------

	/**
	 * Dispatcher confirms a route has been set, locked, and the entry signal cleared.
	 *
	 * Carries the full [route] description, the cleared [aspect], and a
	 * [MovementAuthority][VocabMovementAuthority] bounding the authority to proceed.
	 *
	 * Real-world analogue: "postaveno a volno" — route is set and the signal is clear
	 * (SŽDC D1; AŽD ESA 11 route-grant sequence).
	 *
	 * @property route  The granted train route (from signal, switches, blocks).
	 * @property aspect The aspect now shown at the entry signal.
	 * @property ma     Movement authority bounding how far the train may proceed.
	 */
	@Serializable
	@SerialName("route_grant")
	@LLMDescription("Dispatcher grants a route and clears the entry signal (postaveno a volno).")
	data class RouteGrant(
		override val messageId: String,
		override val sender: AgentRef,
		override val receiver: AgentRef,
		override val simTime: Long,
		override val trainNumber: String?,
		@LLMDescription("The granted train route including switch settings and block sections.")
		val route: TrainRoute,
		@LLMDescription("The aspect now shown at the entry signal.")
		val aspect: Aspect,
		@LLMDescription("Movement authority bounding how far the train may proceed.")
		val ma: VocabMovementAuthority
	) : Message {
		override fun humanReadable(): String = "Pro vlak $trainNumber postaveno a volno (${aspect.humanLabel()})."
	}

	// -------------------------------------------------------------------------
	// 3. RouteDenial — Dispatcher → Train
	// -------------------------------------------------------------------------

	/**
	 * Dispatcher denies a route request.
	 *
	 * Sent when the interlocking refuses the route (section occupied, conflicting
	 * route active, switch unavailable, etc.) or the dispatcher holds the train.
	 *
	 * Real-world analogue: "Nikoliv, čekejte." (D1/D2).
	 *
	 * @property reason Human-readable denial reason (e.g. "úsek U7 obsazen").
	 */
	@Serializable
	@SerialName("route_denial")
	@LLMDescription("Dispatcher denies a route request (Nikoliv, čekejte).")
	data class RouteDenial(
		override val messageId: String,
		override val sender: AgentRef,
		override val receiver: AgentRef,
		override val simTime: Long,
		override val trainNumber: String?,
		@LLMDescription("Human-readable reason for the denial, e.g. section occupied.")
		val reason: String
	) : Message {
		override fun humanReadable(): String = "Nikoliv, čekejte. Vlak $trainNumber: $reason."
	}

	// -------------------------------------------------------------------------
	// 4. MovementAuthority — Dispatcher → Train
	// -------------------------------------------------------------------------

	/**
	 * Dispatcher issues or extends a movement authority to a train.
	 *
	 * May be sent alongside or independently of a [RouteGrant] — for example when
	 * the dispatcher extends an existing authority to the next block boundary.
	 *
	 * Real-world analogue: "oprávnění k jízdě" / "rozkaz k odjezdu" (SŽDC D1, ETCS MA).
	 *
	 * @property authority The movement authority value (target signal, speed, end of authority).
	 */
	@Serializable
	@SerialName("movement_authority")
	@LLMDescription("Dispatcher issues or extends a movement authority (oprávnění k jízdě / rozkaz k odjezdu).")
	data class MovementAuthority(
		override val messageId: String,
		override val sender: AgentRef,
		override val receiver: AgentRef,
		override val simTime: Long,
		override val trainNumber: String?,
		@LLMDescription("Movement authority value: target signal, speed limit, and end-of-authority block.")
		val authority: VocabMovementAuthority
	) : Message {
		override fun humanReadable(): String =
			"Rozkaz k odjezdu vlak $trainNumber: jeďte do ${authority.target.name}, " +
				"max ${authority.speedLimitKmh} km/h."
	}

	// -------------------------------------------------------------------------
	// 5. PositionReport — Train → Dispatcher
	// -------------------------------------------------------------------------

	/**
	 * Train reports its current block position and speed to the dispatcher.
	 *
	 * Sent at each block boundary or on a configurable polling interval.
	 *
	 * Real-world analogue: "poloha vlaku" reporting (D1/D2, AŽD ESA 11 GTN display).
	 *
	 * @property block    The block section the train currently occupies.
	 * @property speedKmh Current speed in km/h (rounded to nearest integer).
	 */
	@Serializable
	@SerialName("position_report")
	@LLMDescription("Train reports its current block position and speed to the dispatcher (poloha vlaku).")
	data class PositionReport(
		override val messageId: String,
		override val sender: AgentRef,
		override val receiver: AgentRef,
		override val simTime: Long,
		override val trainNumber: String?,
		@LLMDescription("Block section the train currently occupies.")
		val block: BlockId,
		@LLMDescription("Current train speed in kilometres per hour.")
		val speedKmh: Int
	) : Message {
		override fun humanReadable(): String = "Vlak $trainNumber v úseku ${block.name}, rychlost $speedKmh km/h."
	}

	// -------------------------------------------------------------------------
	// 6. OccupancyReport — Interlocking → Dispatcher
	// -------------------------------------------------------------------------

	/**
	 * Interlocking reports that a block section has been entered or cleared.
	 *
	 * Triggered by track circuit activation/deactivation or axle counter.
	 * Used by the dispatcher to maintain a real-time occupancy model.
	 *
	 * Real-world analogue: "odhláška" (departure clearance report) and track circuit
	 * indication (AŽD ESA 11 kolejový obvod / počítač náprav).
	 *
	 * @property block    The block section whose occupancy changed.
	 * @property occupied True if the block is now occupied; false if it is clear.
	 */
	@Serializable
	@SerialName("occupancy_report")
	@LLMDescription("Interlocking reports a block section entering or leaving occupied state (kolejový obvod indikace).")
	data class OccupancyReport(
		override val messageId: String,
		override val sender: AgentRef,
		override val receiver: AgentRef,
		override val simTime: Long,
		override val trainNumber: String?,
		@LLMDescription("Block section whose occupancy state changed.")
		val block: BlockId,
		@LLMDescription("True if the block is now occupied (OBSAZENO), false if clear (VOLNO).")
		val occupied: Boolean
	) : Message {
		override fun humanReadable(): String =
			if (occupied) {
				"Vlak $trainNumber vstoupil do úseku ${block.name}."
			} else {
				"Úsek ${block.name} volný."
			}
	}

	// -------------------------------------------------------------------------
	// 7. HoldOrder — Dispatcher → Train
	// -------------------------------------------------------------------------

	/**
	 * Dispatcher orders the train to stop at or before a given signal.
	 *
	 * Used during conflict resolution (one train is held while another proceeds)
	 * or in response to an emergency. The train must stop before [atSignal].
	 *
	 * Real-world analogue: "Stůj" / "mimořádné zastavení" (D1).
	 *
	 * @property atSignal The signal at which the train must stop.
	 */
	@Serializable
	@SerialName("hold_order")
	@LLMDescription("Dispatcher orders the train to stop at or before a signal (Stůj / mimořádné zastavení).")
	data class HoldOrder(
		override val messageId: String,
		override val sender: AgentRef,
		override val receiver: AgentRef,
		override val simTime: Long,
		override val trainNumber: String?,
		@LLMDescription("Signal at which the train must stop.")
		val atSignal: SignalId
	) : Message {
		override fun humanReadable(): String = "Stůj u návěstidla ${atSignal.name}. Vlak $trainNumber."
	}

	// -------------------------------------------------------------------------
	// 8. ConflictNotification — Dispatcher → Dispatcher (or Dispatcher → Train)
	// -------------------------------------------------------------------------

	/**
	 * Notification of a conflict at a contended block section.
	 *
	 * Sent when two or more trains have requested routes over the same block section
	 * and the dispatcher must decide priority. In a multi-dispatcher setup this
	 * maps to the "nabídka/přijetí" (offer/acceptance) and "traťový souhlas"
	 * (line-direction agreement) protocols of D1/D2.
	 *
	 * Real-world analogue: "nabídka/přijetí", "traťový souhlas" (D1/D2).
	 *
	 * @property block     The contended block section.
	 * @property competing Train numbers of all trains competing for the block.
	 */
	@Serializable
	@SerialName("conflict_notification")
	@LLMDescription("Conflict at a contended block: multiple trains competing for the same section (nabídka/přijetí).")
	data class ConflictNotification(
		override val messageId: String,
		override val sender: AgentRef,
		override val receiver: AgentRef,
		override val simTime: Long,
		override val trainNumber: String?,
		@LLMDescription("Block section that is contested by multiple trains.")
		val block: BlockId,
		@LLMDescription("Train numbers of all trains competing for the block section.")
		val competing: List<String>
	) : Message {
		override fun humanReadable(): String = "Konflikt na úseku ${block.name} mezi vlaky ${competing.joinToString()}."
	}
}
