/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import kotlinx.coroutines.runBlocking

/**
 * Shared fixture of the kernel-side reservation expiry tests (Issue #1174): the TTL and the
 * simulation times the scenarios sit on, the real wiring's [expiry], and [atSimTimes], which runs
 * a scenario inside a kDisco simulation. Split out when the WARN-suppression pins moved to
 * [ReservationExpiryWarnSuppressionTest] (Issue #1229), so neither class copies the TTL or the
 * simulation-time runner ([expiry] is used by [ReservationExpiryTest] only).
 *
 * No wall-clock time is involved anywhere: the TTL is read against the simulation clock.
 */
abstract class ReservationExpiryTestBase : PathReservationServiceTestBase() {
	protected companion object {
		/** The configured TTL and the sim times the scenarios sit on, all derived from it. */
		val TTL = DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS
		val BELOW_TTL = TTL - 0.5
		val PAST_TTL = TTL + 1.5
		val BELOW_TWO_TTLS = 2 * TTL - 0.5
		val TWO_TTLS = 2 * TTL
		val PAST_TWO_TTLS = 2 * TTL + 1.5
	}

	/** The expiry of the real service wiring: the one the service's own calls trigger. */
	internal val expiry: DefaultReservationExpiry
		get() = (service as DefaultPathReservationService).expiry

	/**
	 * Run each step at its absolute simulation time, in order, inside one kDisco process. A step
	 * that throws fails the run, so assertions may sit inside the steps.
	 */
	protected fun atSimTimes(vararg steps: Pair<Double, () -> Unit>) =
		runBlocking {
			var failure: Throwable? = null
			Simulation
				.create {
					Process.activate(
						object : Process() {
							override suspend fun actions() {
								for ((at, step) in steps) {
									hold(at - currentSimulationTime())
									try {
										step()
									} catch (e: Throwable) {
										failure = failure ?: e
									}
								}
							}
						}
					)
				}.run(steps.last().first + 10.0)
			failure?.let { throw it }
		}
}
