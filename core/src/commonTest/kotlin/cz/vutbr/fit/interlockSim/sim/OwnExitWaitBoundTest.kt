/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1299: the wait for the own exit is bounded.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.runSampled
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.get
import kotlin.test.Test

/**
 * The bound on the wait for the own exit (Issue #1299, TSE ruling 3): a train whose timetable exit
 * is a candidate but not available holds out for it, but a holder that **never** releases must not
 * hold the train up for ever — after [ReservationTargetPolicy.OWN_EXIT_WAIT_BOUND_SECONDS] of
 * simulation time the fixed-order fallback takes the other exit. `ShuntingLoop` has no SP7
 * watchdog, so this bound is the only thing that breaks such a wait.
 *
 * One seeded train on `cerveny_ujezd.xml` (seed 1163: the first train runs `west_entry_1` →
 * `east_exit_1`), under the synchronous rule-based dispatcher. A stand-in occupant is put on the
 * exit block of `east_exit_1` at the first control step and never leaves, so `east_exit_1` stays
 * a candidate that is never available.
 */
class OwnExitWaitBoundTest : CommonKoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		/** The [CervenyUjezdRunTest] seed: its first train is timetabled to [EAST_EXIT_1]. */
		private const val SEED: Long = 1163L
		private const val END_TIME: Long = 600L
		private const val HOLDER: String = "Holder"
		private const val EAST_EXIT_1: String = "east_exit_1"
		private const val EAST_EXIT_2: String = "east_exit_2"
	}

	/** A stand-in for a train that has stopped on a block for good: it never moves and never leaves. */
	private object StandingHolder : TrackOccupant {
		override val name: String = HOLDER

		override fun distanceToSemaphore(): Double = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}

	@Test
	fun `a holder that never releases cannot keep a train waiting beyond the bound`() {
		val ctx: DefaultSimulationContext =
			CommonTestFixtures.parseSimulationContext(NetworkResources.CERVENY_UJEZD_XML, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val registry = ctx.scope.get<PathReservationRegistry>()
		val loop = ShuntingLoop(ctx, END_TIME, seed = SEED, maxTrains = 1)
		wireSynchronousDispatcher(ctx, loop)
		val exitBlock =
			Station
				.from(ctx)
				.entryExitBlocks
				.single { it.inOut?.name == EAST_EXIT_1 }
				.block

		val dispatcherStep = checkNotNull(loop.controlStepListener)
		var holderPlaced = false
		var trainName: String? = null
		var destination: String? = null
		var ownExitFirstBlockedAt: Double? = null
		var exitTaken: String? = null
		var exitReservedAt: Double? = null
		loop.controlStepListener =
			ControlStepListener {
				if (!holderPlaced) {
					placeHolder(registry, exitBlock)
					holderPlaced = true
				}
				val now = Process.time()
				val train = loop.getApprovedTrains().firstOrNull()
				if (train != null) {
					trainName = train.name
					destination = train.timetableDestinationName
					val blocked =
						(loop.getInnerBlockInputs() + loop.getOuterBlockInputs()).any { input ->
							input.ownerTrainId == train.name && listsBlockedExit(input, train.timetableDestinationName)
						}
					if (blocked && ownExitFirstBlockedAt == null) ownExitFirstBlockedAt = now
				}
				dispatcherStep.onControlStep()
				val target = train?.let { registry.getPathInfo(it.name)?.target }
				if (target is DynamicInOut && exitTaken == null) {
					exitTaken = target.name
					exitReservedAt = now
				}
			}
		ctx.setMainProcess(loop)

		runSampled(ctx) { }

		logger.info {
			"Own-exit wait bound run: train=$trainName destination=$destination " +
				"ownExitFirstBlockedAt=$ownExitFirstBlockedAt exitTaken=$exitTaken exitReservedAt=$exitReservedAt " +
				"exited=${loop.getTrainsExited()} holder=${registry.getOwner(exitBlock)}"
		}
		assertThat(destination, name = "the train's timetable exit").isEqualTo(EAST_EXIT_1)
		assertThat(registry.getOwner(exitBlock), name = "the holder never released its block").isEqualTo(HOLDER)
		assertThat(ownExitFirstBlockedAt, name = "first tick the own exit was blocked").isNotNull()
		assertThat(exitTaken, name = "exit taken after the bound").isEqualTo(EAST_EXIT_2)
		assertThat(loop.getTrainsExited(), name = "trains exited").isEqualTo(1)
		val waited = checkNotNull(exitReservedAt) - checkNotNull(ownExitFirstBlockedAt)
		assertThat(waited, name = "simulation seconds held out for the own exit before the fallback")
			.isGreaterThanOrEqualTo(ReservationTargetPolicy.OWN_EXIT_WAIT_BOUND_SECONDS)
	}

	/** Whether [input] lists [exitName] as a station exit that is not available. */
	private fun listsBlockedExit(
		input: BlockInputObservation,
		exitName: String
	): Boolean = input.candidateTargets.any { it.kind == SeparatorKind.IN_OUT && it.name == exitName && !it.available }

	/**
	 * Puts [StandingHolder] on [exitBlock] the way a train would stand there: reserved from its
	 * signal end, registered with the registry, then entered — and never released.
	 */
	private fun placeHolder(
		registry: PathReservationRegistry,
		exitBlock: DynamicTrackBlock
	) {
		val signalEnd = exitBlock.ends().filterIsInstance<DynamicRailSemaphore>().single()
		exitBlock.setUpPath(signalEnd, HOLDER)
		check(registry.registerAtomic(HOLDER, listOf(exitBlock)) is PathReservationRegistry.RegistrationResult.Success) {
			"The holder could not register the exit block of $EAST_EXIT_1"
		}
		exitBlock.enter(StandingHolder)
	}
}
