/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("UncaughtSimulationExceptions — a process exception is recorded, not lost, and the run goes on")
class UncaughtSimulationExceptionsTest {
	@Test
	fun `a FATAL thrown inside a process is recorded and the simulation still reaches its end`() {
		var endTime = -1.0
		val result =
			UncaughtSimulationExceptions.record {
				runBlocking {
					val sim =
						Simulation.create {
							Process.activate(fatalAfter(1.0, "Wrong state: FREE , expected : RESERVED"))
							Process.activate(recordsArrivalAt(5.0) { endTime = it })
						}
					sim.run(10.0)
				}
			}

		assertThat(result.uncaught, "exceptions that escaped a process").hasSize(1)
		assertThat(result.uncaught.single()).isInstanceOf<SimulationException>()
		assertThat(endTime, "the other process still ran after the FATAL").isEqualTo(5.0)
	}

	@Test
	fun `two FATALs are both recorded in order and a third process still reaches its end`() {
		var thirdArrival = -1.0
		val result =
			UncaughtSimulationExceptions.record {
				runBlocking {
					val sim =
						Simulation.create {
							Process.activate(fatalAfter(1.0, "Wrong state: FREE , expected : RESERVED"))
							Process.activate(fatalAfter(2.0, "Wrong state: OCCUPIED , expected : RESERVED"))
							Process.activate(recordsArrivalAt(5.0) { thirdArrival = it })
						}
					sim.run(10.0)
				}
			}

		assertThat(result.uncaught, "both FATALs are recorded, none is swallowed").hasSize(2)
		assertThat(result.uncaught[0].message, "the first FATAL, in dying order")
			.isEqualTo("Wrong state: FREE , expected : RESERVED")
		assertThat(result.uncaught[1].message, "the second FATAL, in dying order")
			.isEqualTo("Wrong state: OCCUPIED , expected : RESERVED")
		assertThat(thirdArrival, "a third process still runs after both FATALs").isEqualTo(5.0)
	}

	/** A process that dies with [message] after [holdSeconds] of simulated time. */
	private fun fatalAfter(
		holdSeconds: Double,
		message: String
	): Process =
		object : Process() {
			override suspend fun actions() {
				hold(holdSeconds)
				throw SimulationException(message)
			}
		}

	/** A process that reports its arrival time through [record] after [holdSeconds]. */
	private fun recordsArrivalAt(
		holdSeconds: Double,
		record: (Double) -> Unit
	): Process =
		object : Process() {
			override suspend fun actions() {
				hold(holdSeconds)
				record(time())
			}
		}
}
