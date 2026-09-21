/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.UncaughtSimulationExceptions
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.RouteRelease
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEventListener
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent as NavigationBlockEvent

/**
 * Issue #1050: the whole-route release (`releaseRoute`, the LLM's `cancel_route`) has the same
 * `hold(1.0)` window as Issue #1025. A train that has read a proceed aspect is inside the next
 * block before it books it; a release that frees the block in that second kills the train's
 * process with `SimulationException[FATAL]: Wrong state: FREE , expected : RESERVED`.
 *
 * The scenario is [Issue1025CommittedTrainReleaseScenario]'s, with the production whole-route
 * release in place of the partial releaser: catch a train at a proceed aspect with its next block
 * still RESERVED, call `releaseRouteDetailed` there, and require that the block stays reserved and
 * registered, the caller is told the release was deferred, no train process dies, and the train
 * books the block afterwards.
 *
 * Review round addition: once the committed train BOOKS the deferred block, the repeat release the
 * outcome messages tell the dispatcher to make must keep that block registered and OCCUPIED (the
 * occupied-kept rule), never fall through to the wholesale unregister that would emit
 * `BlockReleased` for a block a train stands on.
 */
@DisplayName("Issue #1050 — a whole-route release inside the hold(1.0) window must not free a committed block")
@Tag("integration-test")
class Issue1050WholeRouteReleaseApproachLockTest : DispatcherKoinTestBase() {
	private class Injection(
		val trainId: String,
		val block: DynamicTrackBlock,
		val blockId: String,
		val simTime: Double,
		val outcome: RouteRelease,
		val ownerAfter: String?,
		val stateAfter: TrackFacility.State
	)

	private class Repeat(
		val outcome: RouteRelease,
		val ownerAfter: String?,
		val stateAfter: TrackFacility.State
	)

	@Test
	@Timeout(value = 3, unit = TimeUnit.MINUTES)
	fun `releaseRoute at a proceed aspect defers the committed block and the train books it`() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()
		val loop = prepareShuntingLoop(context, SIM_END_TIME)
		val registry = context.scope.get<PathReservationRegistry>()
		val perception = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
		val actuator = DefaultNetworkActuatorPort(env = context)

		val injection = AtomicReference<Injection?>(null)
		val watch = watchBookedBlock(context, injection)
		val repeat = AtomicReference<Repeat?>(null)
		val injector =
			object : Process() {
				override suspend fun actions() {
					while (injection.get() == null) {
						hold(Issue1025CommittedTrainReleaseScenario.POLL_PERIOD_SECONDS)
						val (trainId, block) =
							Issue1025CommittedTrainReleaseScenario.committedFront(perception, registry) ?: continue
						val outcome = actuator.releaseRouteDetailed(trainId)
						injection.set(
							Injection(
								trainId,
								block,
								BlockIdentity.stableBlockId(block),
								time(),
								outcome,
								registry.getOwner(block),
								block.getState()
							)
						)
					}

					// Review round (Issue #1050): the train books the deferred block; the repeat
					// release must keep the now-OCCUPIED block registered, not unregister the
					// train wholesale. This is the exact retry the PARTIAL outcome message tells
					// the dispatcher to make.
					while (watch.bookedAt.get() == null) {
						hold(Issue1025CommittedTrainReleaseScenario.POLL_PERIOD_SECONDS)
					}
					val done = injection.get()!!
					val outcome = actuator.releaseRouteDetailed(done.trainId)
					repeat.set(Repeat(outcome, registry.getOwner(done.block), done.block.getState()))
				}
			}
		Issue1025CommittedTrainReleaseScenario.installInjectorActivation(loop, injector)

		val run = UncaughtSimulationExceptions.record { context.run() }

		assertThat(run.uncaught, "exceptions that escaped a train process (the #1025/#1050 FATAL)").isEmpty()
		val done = injection.get()
		assertThat(done, "injection (null means no train was caught at a proceed aspect: vacuous)").isNotNull()
		assertThat(done!!.outcome.deferred, "the caller learns the release was partial").isTrue()
		assertThat(done.outcome.deferredBlockIds, "deferred blocks").contains(done.blockId)
		assertThat(done.stateAfter, "state of the committed block right after the release")
			.isEqualTo(TrackFacility.State.RESERVED)
		assertThat(done.ownerAfter, "registry owner of the committed block right after the release")
			.isEqualTo(done.trainId)
		val bookedAt = watch.bookedAt.get()
		assertThat(bookedAt, "sim time at which the committed train booked the block").isNotNull()
		assertThat(bookedAt!! > done.simTime, "booking ($bookedAt) after the release call (${done.simTime})").isTrue()

		val afterBooking = repeat.get()
		assertThat(afterBooking, "the repeat release after the booking (null means phase 2 never ran)").isNotNull()
		assertThat(
			afterBooking!!.outcome.deferred,
			"the repeat still reports a partial release for the occupied block"
		).isTrue()
		assertThat(afterBooking.outcome.deferredBlockIds, "the repeat still defers the booked block").contains(done.blockId)
		assertThat(afterBooking.stateAfter, "the booked block stays OCCUPIED through the repeat release")
			.isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(afterBooking.ownerAfter, "the booked block stays registered to its train").isEqualTo(done.trainId)
		// A BlockReleased for the block while the train stands on it is the registry-vs-physical
		// divergence; a later one, after the train left, is a legitimate reclaim.
		val firstReleasedAt = watch.firstReleasedAt.get()
		if (firstReleasedAt != null) {
			val clearedAt = watch.clearedAt.get()
			assertThat(clearedAt, "sim time at which the train left the block before it was released").isNotNull()
			// Same-instant clear-then-release is the normal hand-off as the train moves on;
			// a release BEFORE the clear is the divergence.
			assertThat(
				firstReleasedAt >= clearedAt!!,
				"BlockReleased ($firstReleasedAt) not before the train left the block ($clearedAt)"
			).isTrue()
		}
	}

	/**
	 * Watches the deferred block's lifecycle: when the committed train books it, when it leaves,
	 * and when the registry first reports the block released.
	 */
	private fun watchBookedBlock(
		context: DefaultSimulationContext,
		injection: AtomicReference<Injection?>
	): BookedBlockWatch {
		val watch = BookedBlockWatch()
		context.addBlockEventListener(
			BlockEventListener { event ->
				val done = injection.get() ?: return@BlockEventListener
				when (event) {
					is BlockEvent.OccupancySet -> {
						val isBookedBlock = BlockIdentity.stableBlockId(event.block) == done.blockId
						if (event.occupant.name == done.trainId &&
							isBookedBlock &&
							watch.bookedAt.get() == null
						) {
							watch.bookedAt.set(event.time)
						}
					}

					is BlockEvent.OccupancyCleared -> {
						val isBookedBlock = BlockIdentity.stableBlockId(event.block) == done.blockId
						if (isBookedBlock && watch.clearedAt.get() == null) {
							watch.clearedAt.set(event.time)
						}
					}
				}
			}
		)
		context.onBlockEvent { event ->
			val done = injection.get() ?: return@onBlockEvent
			if (event is NavigationBlockEvent.BlockReleased &&
				event.trainId == done.trainId &&
				BlockIdentity.stableBlockId(event.block) == done.blockId &&
				watch.firstReleasedAt.get() == null
			) {
				watch.firstReleasedAt.set(event.time)
			}
		}
		return watch
	}

	/** What [watchBookedBlock] records about the deferred block. */
	private class BookedBlockWatch {
		val bookedAt = AtomicReference<Double?>(null)
		val clearedAt = AtomicReference<Double?>(null)
		val firstReleasedAt = AtomicReference<Double?>(null)
	}

	private companion object {
		const val SIM_END_TIME = 300L
	}
}
