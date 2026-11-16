/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1387: the platform choice prefers a track from which the train's own exit is reachable.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isIn
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.util.cellsOfType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.component.get

/**
 * Issue #1387 (traffic-simulation-expert ruling, option (a)): on the Praha station the platform
 * track is chosen at the home-signal hop by [ReservationTargetPolicy.pick]. A train is routed to a
 * platform signal from which its own timetable exit is reachable when one is available
 * ([BlockInputObservation.destinationReachableVia]); otherwise the old fixed order applies.
 *
 * The Praha control-arm traffic of the `prahaHlavniNadrazi` example: 8 trains, at most 4 at a
 * time, switch-legal pairs only, 600 s, rule-based dispatcher, seeds 1366, 0 and 1 (the #1387
 * acceptance seeds). Every train that leaves by another exit is classified (class A: its own exit
 * was never offered; class B: offered but not taken). Blind class A (no platform toward the own
 * exit was free) must be 0. A fallback class A — every platform toward the exit busy at the pick,
 * the owner's "fallback stays" clause — is counted, not failed: on seeds 1366 and 1 one train each
 * takes that fallback (traffic-simulation-expert ruling on #1387: report it, do not widen the
 * change; the acceptance "class A = 0" is the owner's to decide).
 */
@Tag("integration-test")
class PrahaEntryPlatformExitTest : KoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		const val END_TIME: Long = 600L
		const val TRAINS: Int = 8
		const val MAX_CONCURRENT: Int = 4
		const val WRONG_OUT_SUFFIX: String = " ends in wrong out"
	}

	private fun prahaContext(): DefaultSimulationContext {
		val ctx: DefaultSimulationContext =
			CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		return ctx
	}

	private fun prahaLoop(
		ctx: DefaultSimulationContext,
		seed: Long
	): ShuntingLoop =
		ShuntingLoop(
			ctx,
			END_TIME,
			maxConcurrentTrains = MAX_CONCURRENT,
			seed = seed,
			maxTrains = TRAINS,
			switchLegalPairsOnly = true
		)

	@Test
	fun `the exits reachable beyond a platform signal are the ones the issue names`() {
		val ctx = prahaContext()
		val loop = prahaLoop(ctx, seed = 0L)

		fun beyond(name: String): Set<String> =
			loop.exitsReachableBeyond(ctx.getRailWayNetGrid().cellsOfType<DynamicRailSemaphore>().single { it.name == name })

		// From these platform tracks the exit signal offers only one exit (Issue #1387 body).
		assertThat(beyond("S9")).isEqualTo(setOf("N-Vys-1"))
		assertThat(beyond("S6")).isEqualTo(setOf("N-Lib-2"))
		assertThat(beyond("S3")).isEqualTo(setOf("N-Lib-1"))
	}

	@ParameterizedTest(name = "seed {0}")
	@ValueSource(longs = [1366L, 0L, 1L])
	fun `trains get a platform toward their own exit when one is free, blind class A is zero`(seed: Long) {
		val ctx = prahaContext()
		val loop = prahaLoop(ctx, seed)
		wireSynchronousDispatcher(ctx, loop)
		val dispatcherStep = checkNotNull(loop.controlStepListener)
		var steeredPicks = 0
		loop.controlStepListener =
			ControlStepListener {
				for (input in loop.getInnerBlockInputs() + loop.getOuterBlockInputs()) {
					steeredPicks += checkObservation(input)
				}
				dispatcherStep.onControlStep()
			}
		ctx.setMainProcess(loop)

		var wrongOutReports = 0
		runSampled(ctx) { message ->
			if (message?.endsWith(WRONG_OUT_SUFFIX) == true) wrongOutReports++
		}

		logger.info {
			"Praha #1387 run: seed=$seed entered=${loop.getTrainsEntered()} exited=${loop.getTrainsExited()} " +
				"wrongOut=$wrongOutReports classA=${loop.getWrongOutClassA()} classB=${loop.getWrongOutClassB()} " +
				"classAFallback=${loop.getWrongOutClassAFallback()} " +
				"steeredPicks=$steeredPicks"
		}
		assertThat(loop.getTrainsEntered(), name = "trains entered").isEqualTo(TRAINS)
		assertThat(loop.getTrainsExited(), name = "trains exited").isEqualTo(TRAINS)
		assertThat(loop.getWrongOutClassA() + loop.getWrongOutClassB(), name = "classified wrong-out trains")
			.isEqualTo(wrongOutReports)
		assertThat(
			loop.getWrongOutClassA() - loop.getWrongOutClassAFallback(),
			name = "blind class A: own exit never offered and no platform toward it free"
		).isZero()
		// Every seed had class A wrong-outs before #1387, so the reachable set must have changed picks.
		assertThat(steeredPicks, name = "picks changed by the reachable set").isGreaterThan(0)
	}

	/**
	 * Checks the #1387 invariants of one observation and returns 1 when the reachable set changed
	 * the pick (the old fixed order would have taken another candidate), else 0.
	 */
	private fun checkObservation(input: BlockInputObservation): Int {
		val reachable = input.destinationReachableVia
		val ownExitListed =
			input.candidateTargets.any { it.kind == SeparatorKind.IN_OUT && it.name == input.destinationName }
		if (ownExitListed || input.awaitingRouteExtension || input.destinationName == null) {
			// TSE fixes 1 and 2: the #1299 wait and the #1244 guard see the old observation.
			assertThat(reachable, name = "reachable set of ${input.blockId}>${input.towardSemaphoreName}").isEmpty()
			return 0
		}
		val signalNames = input.candidateTargets.filter { it.kind == SeparatorKind.SEMAPHORE }.map { it.name }
		reachable.forEach { assertThat(it, name = "reachable candidate").isIn(*signalNames.toTypedArray()) }
		val oldPick = input.copy(destinationReachableVia = emptySet()).chosenTargetName()
		// The owner's fallback clause: with every reachable signal busy the pick is the old one.
		val allReachableBusy =
			input.copy(
				candidateTargets = input.candidateTargets.map { if (it.name in reachable) it.copy(available = false) else it }
			)
		assertThat(allReachableBusy.chosenTargetName(), name = "pick with every reachable signal busy")
			.isEqualTo(allReachableBusy.copy(destinationReachableVia = emptySet()).chosenTargetName())
		val pick = input.chosenTargetName()
		if (input.candidateTargets.any { it.available && it.name in reachable }) {
			assertThat(pick in reachable, name = "pick $pick is reachable for ${input.destinationName}").isTrue()
		} else {
			assertThat(pick, name = "fallback pick").isEqualTo(oldPick)
		}
		return if (pick != oldPick) 1 else 0
	}
}
