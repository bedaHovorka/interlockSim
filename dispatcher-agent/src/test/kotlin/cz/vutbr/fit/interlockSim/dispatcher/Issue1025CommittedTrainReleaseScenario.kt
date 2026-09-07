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
import assertk.assertions.isEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.testutil.UncaughtSimulationExceptions
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.events.BlockEvent
import cz.vutbr.fit.interlockSim.sim.events.BlockEventListener
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #1025, the committed-train window: a train that has just been given a proceed aspect is
 * physically inside the next block before it books it.
 *
 * ## The window
 *
 * `Train.Front.semaphoreAction` reads the signal, starts (or keeps) moving, sleeps `hold(1.0)` and
 * only then calls `DynamicTrackBlock.enter` on the next block. The position integrator does not
 * pause during that second, so the front is metres past the signal when `enter` runs. `enter`
 * asserts RESERVED → OCCUPIED: if anything freed the block during that second, it throws
 * `SimulationException[FATAL]: Wrong state: FREE , expected : RESERVED` on the simulation thread —
 * the #1025 stack trace. The dispatcher's control step runs every 2.0 s on that same thread, so a
 * release from `OrphanReservationSweeper` → `RegistryPartialRouteReleaser` can land inside the
 * window whenever a front crosses a proceed aspect in the second before a control step.
 *
 * ## What this pins
 *
 * A route release must respect approach locking: a reserved block whose governing signal shows a
 * proceed aspect may have a train committed to it, so the release may drop the signal to STOP but
 * must not free the block in the same call. The block is freed on a later call, once the signal
 * has been at STOP long enough for a committed train to have booked the block (which makes the
 * block OCCUPIED and therefore ineligible).
 *
 * The scenario drives the rule-based shunting loop, watches for the first train that stands at a
 * proceed aspect with its next block still RESERVED (the perception port publishes exactly that
 * pair: `frontSectionName` names the block the front is entering while the block reading still
 * says RESERVED), and calls the production releaser on that block inside the window. Before the
 * fix the run dies with the FATAL. After it, the call frees nothing, the train books the block a
 * second later, and the run completes.
 *
 * [Issue1025CommittedTrainReleaseTest] runs this once as the CI-facing integration test;
 * [Issue1025CommittedTrainReleaseHeavyTest] repeats it as a manual soak under the `heavy-test`
 * tag — the assertions live here, once, so the two runners cannot drift apart.
 */
internal object Issue1025CommittedTrainReleaseScenario {
	/**
	 * Runs the scenario once and asserts the approach-lock outcome — see the object KDoc.
	 *
	 * @param context a tracked context from `TestFixtures.newShuntingSimulationContext()`, supplied
	 *   by the caller (the `tracked` extension belongs to the Koin test bases the runners extend).
	 */
	fun runOnceAndAssert(context: DefaultSimulationContext) {
		val loop = prepareShuntingLoop(context, SIM_END_TIME)
		val registry = context.scope.get<PathReservationRegistry>()
		val service = context.getRoutingServices().getPathReservationService()
		val perception = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
		val releaser = RegistryPartialRouteReleaser(registry = registry, pathReservationService = service)

		val injection = AtomicReference<Injection?>(null)
		val bookedAfterInjection = watchBookingAfterInjection(context, injection)
		installInjectorActivation(loop, committedFrontInjector(injection, perception, registry, releaser))

		// Before the fix the train's process dies with SimulationException[FATAL]
		// "Wrong state: FREE , expected : RESERVED" while the run goes on without it.
		val run = UncaughtSimulationExceptions.record { context.run() }

		assertThat(run.uncaught, "exceptions that escaped a train process (the #1025 FATAL)").isEmpty()
		val done = injection.get()
		assertThat(done, "injection (null means no train was caught at a proceed aspect: vacuous)").isNotNull()
		assertThat(done!!.outcome.deferred, "release deferred by approach locking (not a vacuous refusal)").isTrue()
		assertThat(done.outcome.released, "blocks freed while a proceed aspect stood at the boundary").isEmpty()
		val booked = bookedAfterInjection.get()
		assertThat(booked, "sim time at which the committed train booked the block after the release call")
			.isNotNull()
		assertThat(booked!! > done.simTime, "booking ($booked) came after the release call (${done.simTime})")
			.isTrue()
	}

	private class Injection(
		val trainId: String,
		val blockId: String,
		val simTime: Double,
		val outcome: TailRelease
	)

	/**
	 * Registers the listener that records when the committed train books the injected block after
	 * the release call. `DynamicTrackBlock.enter` fires [BlockEvent.OccupancySet] through the
	 * environment's block-event bus. Listeners run on the simulation thread only, so
	 * first-writer-wins is race-free.
	 */
	private fun watchBookingAfterInjection(
		context: DefaultSimulationContext,
		injection: AtomicReference<Injection?>
	): AtomicReference<Double?> {
		val bookedAfterInjection = AtomicReference<Double?>(null)
		context.addBlockEventListener(
			BlockEventListener { event ->
				val done = injection.get() ?: return@BlockEventListener
				if (event is BlockEvent.OccupancySet &&
					event.occupant.name == done.trainId &&
					BlockIdentity.stableBlockId(event.block) == done.blockId
				) {
					if (bookedAfterInjection.get() == null) bookedAfterInjection.set(event.time)
				}
			}
		)
		return bookedAfterInjection
	}

	/** The process that polls the live state every [POLL_PERIOD_SECONDS] and strikes once, inside the window. */
	private fun committedFrontInjector(
		injection: AtomicReference<Injection?>,
		perception: DefaultNetworkPerceptionPort,
		registry: PathReservationRegistry,
		releaser: RegistryPartialRouteReleaser
	): Process =
		object : Process() {
			override suspend fun actions() {
				while (injection.get() == null) {
					hold(POLL_PERIOD_SECONDS)
					val target = committedFront(perception, registry) ?: continue
					val (trainId, block) = target
					val blockId = BlockIdentity.stableBlockId(block)
					val outcome = releaser.releaseUntravelledTail(trainId, listOf(blockId))
					injection.set(Injection(trainId, blockId, time(), outcome))
				}
			}
		}

	/** Wraps the loop's wired listener so [injector] is activated on the first control step only. */
	private fun installInjectorActivation(
		loop: ShuntingLoop,
		injector: Process
	) {
		val wired = loop.controlStepListener ?: error("prepareShuntingLoop did not install a listener")
		var activated = false
		loop.controlStepListener =
			ControlStepListener {
				if (!activated) {
					activated = true
					Process.activate(injector)
				}
				wired.onControlStep()
			}
	}

	/**
	 * The first approved train whose front is entering a block that is still RESERVED for it while
	 * a signal governing that block shows a proceed aspect — that is a train inside the
	 * `hold(1.0)` window. Returns the train id and the live block, or null when no train is there.
	 */
	private fun committedFront(
		perception: DefaultNetworkPerceptionPort,
		registry: PathReservationRegistry
	): Pair<String, DynamicTrackBlock>? {
		val snapshot = perception.captureSnapshot()
		val blocksById = snapshot.blocks.associateBy { it.blockId }
		for (position in snapshot.trainPositions) {
			val frontId = position.frontSectionName ?: continue
			val reading = blocksById[frontId] ?: continue
			if (reading.state != TrackFacility.State.RESERVED || reading.trainId != position.trainId) continue
			val block =
				registry.getBlocks(position.trainId).firstOrNull { BlockIdentity.stableBlockId(it) == frontId }
					?: continue
			if (governingSemaphores(block).any { it.signal.isAllowing() }) {
				return position.trainId to block
			}
		}
		return null
	}

	private fun governingSemaphores(block: DynamicTrackBlock): List<DynamicRailSemaphore> =
		buildList {
			block.ends().forEach { end ->
				when (end) {
					is DynamicRailSemaphore -> add(end)
					is DynamicInOut -> add(end.inSemaphore)
					else -> Unit
				}
			}
			when (val from = block.reservedFrom) {
				is DynamicRailSemaphore -> add(from)
				is DynamicInOut -> add(from.inSemaphore)
				else -> Unit
			}
		}

	private const val SIM_END_TIME = 300L
	private const val POLL_PERIOD_SECONDS = 0.1
}
