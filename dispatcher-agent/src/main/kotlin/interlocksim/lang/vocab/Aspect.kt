package interlocksim.lang.vocab

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Czech railway signal aspect (návěst) — the operating vocabulary's speed-signalling tier.
 *
 * This sealed hierarchy covers the starter aspect set used on Czech secondary lines and
 * shunting (station) operations, derived from the SŽDC/SŽ D1 predpis and the AŽD ESA 11
 * signalling catalogue.  A future SP3.9 slice will grow the set for corridor speeds.
 *
 * ## Correspondence with the simulator's [cz.vutbr.fit.interlockSim.objects.cells.Signal] enum
 *
 * The legacy `Signal` enum (STOP, S30, S40, S60, S80, S100, FREE) represents the internal
 * allowed-speed model of a semaphore during simulation. This `Aspect` sealed interface is
 * the *typed operating vocabulary* exchanged between agents (messages, Koog tool schemas);
 * adapter code in a later slice will bridge between the two representations.
 *
 * | Signal enum      | Aspect subtype             |
 * |------------------|----------------------------|
 * | `STOP`           | `Stuj`                     |
 * | `FREE`           | `Volno`                    |
 * | `S40`            | `Rychlost(40)`             |
 * | `S60`            | `Rychlost(60)`             |
 * | `S80`            | `Rychlost(80)`             |
 * | `S100`           | `Rychlost(100)`            |
 *
 * Shunting aspects (`PosunDovolen`, `PosunZakazan`) have no direct `Signal` equivalent
 * and are expressed through the route-permission protocol instead.
 *
 * ## Serialization
 *
 * Every subtype has a stable `@SerialName` that becomes the protocol discriminator.
 * These names must not change after the first inter-agent exchange.
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
@LLMDescription("Czech railway signal aspect (návěst) used by the SP3 operating language.")
sealed interface Aspect {
	/**
	 * Stůj — stop at the signal.
	 *
	 * Absolute stop aspect; shown as a red light.
	 * The train must stop at or before this signal.
	 */
	@Serializable
	@SerialName("stuj")
	@LLMDescription("Stůj: stop at the signal. Absolute stop — red light.")
	data object Stuj : Aspect

	/**
	 * Volno — proceed at line speed.
	 *
	 * The next signal also shows a proceed aspect.
	 * Shown as a green light.
	 */
	@Serializable
	@SerialName("volno")
	@LLMDescription("Volno: proceed at line speed; the following signal also permits movement — green light.")
	data object Volno : Aspect

	/**
	 * Výstraha — proceed, but expect a stop at the next main signal.
	 *
	 * The driver must reduce speed to stop before the next signal.
	 * Shown as a yellow light.
	 */
	@Serializable
	@SerialName("vystraha")
	@LLMDescription("Výstraha: proceed and expect stop at the next main signal — yellow light.")
	data object Vystraha : Aspect

	/**
	 * Rychlost N km/h — proceed with the given speed limit through adjacent turnouts.
	 *
	 * Lower-arm speed aspect. The [kmh] value is the permitted speed limit.
	 */
	@Serializable
	@SerialName("rychlost")
	@LLMDescription("Rychlost N km/h: proceed with the specified speed limit through adjacent turnouts.")
	data class Rychlost(
		@LLMDescription("Permitted speed in kilometres per hour (e.g. 40, 60, 80, 100).")
		val kmh: Int
	) : Aspect

	/**
	 * Očekávejte rychlost N km/h — prepare for speed limit N at the next signal.
	 *
	 * Distant (upper-arm) speed announcement; the actual speed limit is enforced at the
	 * next signal showing a `Rychlost` aspect.
	 */
	@Serializable
	@SerialName("ocekavejte")
	@LLMDescription("Očekávejte rychlost N km/h: expect speed limit N at the next signal.")
	data class Ocekavejte(
		@LLMDescription("Expected speed limit at the next signal in kilometres per hour.")
		val kmh: Int
	) : Aspect

	/**
	 * Přivolávací návěst — pass a stop signal in degraded mode, on sight.
	 *
	 * Used when normal route setting is not possible. The driver may pass the signal
	 * at no more than 40 km/h on sight (na dohled). Shown as a slow-blinking white
	 * light on a red background.
	 */
	@Serializable
	@SerialName("privolavaci_navest")
	@LLMDescription("Přivolávací návěst: pass a stop signal in degraded mode at ≤40 km/h on sight.")
	data object PrivolavaciNavest : Aspect

	/**
	 * Posun dovolen — shunting movement is permitted.
	 *
	 * Authorises shunting moves at the signal. Shown as a white light.
	 * Corresponds to the seřaďovací (shunting yard) aspect vocabulary in SŽDC D1.
	 */
	@Serializable
	@SerialName("posun_dovolen")
	@LLMDescription("Posun dovolen: shunting movement permitted — white light.")
	data object PosunDovolen : Aspect

	/**
	 * Posun zakázán — shunting movement is prohibited.
	 *
	 * Prohibits shunting moves at the signal. Shown as a blue light.
	 * Corresponds to the seřaďovací (shunting yard) aspect vocabulary in SŽDC D1.
	 */
	@Serializable
	@SerialName("posun_zakazan")
	@LLMDescription("Posun zakázán: shunting movement prohibited — blue light.")
	data object PosunZakazan : Aspect
}
