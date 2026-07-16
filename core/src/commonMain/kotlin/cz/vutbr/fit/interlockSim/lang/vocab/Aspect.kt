package cz.vutbr.fit.interlockSim.lang.vocab

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
 * the *typed operating vocabulary* exchanged between agents (messages, tool schemas);
 * [toSignal] / [cz.vutbr.fit.interlockSim.lang.toAspect] bridge between the two representations.
 *
 * | Signal enum      | Aspect subtype             |
 * |------------------|----------------------------|
 * | `STOP`           | `Stuj`                     |
 * | `FREE`           | `Volno`                    |
 * | `S30`            | `Rychlost(30)`             |
 * | `S40`            | `Rychlost(40)`             |
 * | `S60`            | `Rychlost(60)`             |
 * | `S80`            | `Rychlost(80)`             |
 * | `S100`           | `Rychlost(100)`            |
 *
 * Shunting and degraded aspects (`Vystraha`, `Ocekavejte`, `PrivolavaciNavest`, `PosunDovolen`,
 * `PosunZakazan`) have no direct `Signal` equivalent and are expressed through the
 * route-permission / distant-announcement protocol instead.
 *
 * ## Serialization
 *
 * Every subtype has a stable `@SerialName` that becomes the protocol discriminator.
 * These names must not change after the first inter-agent exchange.
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
sealed interface Aspect {
	/**
	 * Czech human-readable label for this aspect, suitable for log output and [cz.vutbr.fit.interlockSim.lang.proto.Message.humanReadable] text.
	 */
	fun humanLabel(): String =
		when (this) {
			is Stuj -> "Stůj"
			is Volno -> "Volno"
			is Vystraha -> "Výstraha"
			is Rychlost -> "Rychlost $kmh km/h"
			is Ocekavejte -> "Očekávejte rychlost $kmh km/h"
			is PrivolavaciNavest -> "Přivolávací návěst"
			is PosunDovolen -> "Posun dovolen"
			is PosunZakazan -> "Posun zakázán"
		}

	/**
	 * Stůj — stop at the signal.
	 *
	 * Absolute stop aspect; shown as a red light.
	 * The train must stop at or before this signal.
	 */
	@Serializable
	@SerialName("stuj")
	data object Stuj : Aspect

	/**
	 * Volno — proceed at line speed.
	 *
	 * The next signal also shows a proceed aspect.
	 * Shown as a green light.
	 */
	@Serializable
	@SerialName("volno")
	data object Volno : Aspect

	/**
	 * Výstraha — proceed, but expect a stop at the next main signal.
	 *
	 * The driver must reduce speed to stop before the next signal.
	 * Shown as a yellow light.
	 */
	@Serializable
	@SerialName("vystraha")
	data object Vystraha : Aspect

	/**
	 * Rychlost N km/h — proceed with the given speed limit through adjacent turnouts.
	 *
	 * Lower-arm speed aspect. The [kmh] value is the permitted speed limit.
	 */
	@Serializable
	@SerialName("rychlost")
	data class Rychlost(
		val kmh: Int
	) : Aspect {
		init {
			require(kmh > 0) { "Rychlost speed must be positive, got $kmh" }
		}
	}

	/**
	 * Očekávejte rychlost N km/h — prepare for speed limit N at the next signal.
	 *
	 * Distant (upper-arm) speed announcement; the actual speed limit is enforced at the
	 * next signal showing a `Rychlost` aspect.
	 */
	@Serializable
	@SerialName("ocekavejte")
	data class Ocekavejte(
		val kmh: Int
	) : Aspect {
		init {
			require(kmh > 0) { "Očekávejte speed must be positive, got $kmh" }
		}
	}

	/**
	 * Přivolávací návěst — pass a stop signal in degraded mode, on sight.
	 *
	 * Used when normal route setting is not possible. The driver may pass the signal
	 * at no more than 40 km/h on sight (na dohled). Shown as a slow-blinking white
	 * light on a red background.
	 */
	@Serializable
	@SerialName("privolavaci_navest")
	data object PrivolavaciNavest : Aspect

	/**
	 * Posun dovolen — shunting movement is permitted.
	 *
	 * Authorises shunting moves at the signal. Shown as a white light.
	 * Corresponds to the seřaďovací (shunting yard) aspect vocabulary in SŽDC D1.
	 */
	@Serializable
	@SerialName("posun_dovolen")
	data object PosunDovolen : Aspect

	/**
	 * Posun zakázán — shunting movement is prohibited.
	 *
	 * Prohibits shunting moves at the signal. Shown as a blue light.
	 * Corresponds to the seřaďovací (shunting yard) aspect vocabulary in SŽDC D1.
	 */
	@Serializable
	@SerialName("posun_zakazan")
	data object PosunZakazan : Aspect
}
