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

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Util
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Deterministic event-driven block handover tests (Issue #582).
 *
 * Verifies that a train process can wait for a block release using kDisco
 * [waitUntil] and a [Condition] exposed by [PathReservationRegistry], and that
 * the waiting train reserves the block in a later scheduler event without
 * busy-polling or deadlock.
 */
@Tag("integration-test")
@DisplayName("Block release event-driven wait")
class BlockReleaseWaitTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Test
	fun `train B waits for train A to release a block then reserves it in a later event`() =
		runBlocking {
			val xml =
				TestFixtures.loadShuntingXml()
					?: throw IllegalStateException("vyhybna.xml fixture not found")
			val context = simulationContextFactory.createContext(xml) as DefaultSimulationContext
			testContext = context

			val registry = context.scope.get<PathReservationRegistry>()
			val block = Util.assertInstanceOf<DynamicTrackBlock>(context.getGraph().values().first())
			val from = block.ends()[0] as DynamicPathSeparator

			val occupantA = TestOccupant("Train A")
			val occupantB = TestOccupant("Train B")

			val events = mutableListOf<String>()
			var releaseTime = -1.0
			var reserveTime = -1.0

			Simulation
				.create {
					Process.activate(
						object : Process() {
							override suspend fun actions() {
								registry.registerAtomic(occupantA.name, listOf(block))
								block.setUpPath(from, occupantA.name)
								block.enter(occupantA)
								events.add("A-ENTER:${time()}")

								hold(10.0)

								block.leave(occupantA)
								val released = registry.unregisterBlock(occupantA.name, block)
								releaseTime = time()
								events.add("A-RELEASE:${time()}:$released")
							}
						}
					)

					Process.activate(
						object : Process() {
							override suspend fun actions() {
								hold(0.5)
								when (val firstAttempt = registry.registerAtomic(occupantB.name, listOf(block))) {
									is PathReservationRegistry.RegistrationResult.Conflict -> {
										events.add("B-CONFLICT:${time()}:${firstAttempt.existingOwner}")

										waitUntil(registry.createBlockAvailableCondition(block))

										val secondAttempt = registry.registerAtomic(occupantB.name, listOf(block))
										assertThat(secondAttempt).isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
										reserveTime = time()
										events.add("B-RESERVE:${time()}")

										block.setUpPath(from, occupantB.name)
										block.enter(occupantB)
										events.add("B-ENTER:${time()}")
									}

									else -> error("Expected Train B's first reservation attempt to conflict, got $firstAttempt")
								}
							}
						}
					)
				}.run(100.0)

			assertThat(releaseTime).isGreaterThanOrEqualTo(10.0)
			assertThat(reserveTime).isGreaterThanOrEqualTo(releaseTime)
			assertThat(events).hasSize(5)
			assertThat(events[0]).isEqualTo("A-ENTER:0.0")
			assertThat(events[1]).isEqualTo("B-CONFLICT:0.5:Train A")
			assertThat(events[2].startsWith("A-RELEASE:")).isTrue()
			assertThat(events[3]).isEqualTo("B-RESERVE:10.0")
			assertThat(events[4]).isEqualTo("B-ENTER:10.0")

			assertThat(registry.getOwner(block)).isEqualTo(occupantB.name)
			assertThat(block.occupant).isSameInstanceAs(occupantB)
		}

	private class TestOccupant(
		override val name: String
	) : TrackOccupant {
		override fun distanceToSemaphore(): Double = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}
}
