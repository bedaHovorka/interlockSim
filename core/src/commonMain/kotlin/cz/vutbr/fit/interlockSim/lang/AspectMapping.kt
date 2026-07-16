package cz.vutbr.fit.interlockSim.lang

import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.objects.cells.Signal

/**
 * Bidirectional premapping between the legacy simulation [Signal] enum and the SP3 operating
 * vocabulary [Aspect] sealed interface (SP3.2, Issue #570).
 *
 * [Signal] is the internal allowed-speed model of a semaphore used by the kDisco simulation
 * engine; [Aspect] is the typed Czech návěst exchanged between agents. These two functions
 * bridge the two representations where the interlocking meets the agent layer.
 *
 * Grounded in SŽDC/SŽ D1 and the AŽD ESA 11 signalling catalogue:
 * - `STOP` ↔ `Stuj` (červená — absolute stop),
 * - `FREE` ↔ `Volno` (zelená — proceed at line speed),
 * - `S30/S40/S60/S80/S100` ↔ `Rychlost(N)` (dvousvětlová rychlostní návěst).
 *
 * The reverse mapping is partial: the distant, calling-on, and shunting aspects
 * (`Výstraha`, `Očekávejte`, `Přivolávací návěst`, `Posun dovolen`, `Posun zakázán`) and
 * non-standard `Rychlost` speeds (e.g. `Rychlost(50)`) have no `Signal` equivalent — they are
 * expressed via the route-permission / distant-announcement protocol, not via `Signal`.
 *
 * Map a simulation [Signal] to its operating-vocabulary [Aspect]. Total: every [Signal] value
 * has a defined aspect.
 *
 * @since Issue #571 (SP3.3 — Goal 10)
 */
fun Signal.toAspect(): Aspect =
	when (this) {
		Signal.STOP -> Aspect.Stuj
		Signal.S30 -> Aspect.Rychlost(30)
		Signal.S40 -> Aspect.Rychlost(40)
		Signal.S60 -> Aspect.Rychlost(60)
		Signal.S80 -> Aspect.Rychlost(80)
		Signal.S100 -> Aspect.Rychlost(100)
		Signal.FREE -> Aspect.Volno
	}

/**
 * Map an operating-vocabulary [Aspect] back to the simulation [Signal] enum.
 *
 * Partial: returns `null` for aspects that have no `Signal` equivalent
 * (`Vystraha`, `Ocekavejte`, `PrivolavaciNavest`, `PosunDovolen`, `PosunZakazan`) and for
 * `Rychlost(kmh)` speeds that are not one of the modelled `S30/S40/S60/S80/S100` values.
 */
fun Aspect.toSignal(): Signal? =
	when (this) {
		is Aspect.Stuj -> Signal.STOP
		is Aspect.Volno -> Signal.FREE
		is Aspect.Rychlost ->
			when (kmh) {
				30 -> Signal.S30
				40 -> Signal.S40
				60 -> Signal.S60
				80 -> Signal.S80
				100 -> Signal.S100
				else -> null
			}
		is Aspect.Vystraha -> null
		is Aspect.Ocekavejte -> null
		is Aspect.PrivolavaciNavest -> null
		is Aspect.PosunDovolen -> null
		is Aspect.PosunZakazan -> null
	}
