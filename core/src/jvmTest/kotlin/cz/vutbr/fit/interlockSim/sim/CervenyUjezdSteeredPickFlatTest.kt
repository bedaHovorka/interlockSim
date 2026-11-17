/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.SteeredPickObserver
import cz.vutbr.fit.interlockSim.testutil.WRONG_OUT_TRAIN_REPORT_FRAGMENT
import cz.vutbr.fit.interlockSim.testutil.runSampled
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.component.get

/**
 * Issue #1387 (review round): the platform steer must change no pick outside the Praha station. The
 * #1387 pick prefers a platform signal from which the train's own exit is reachable
 * ([BlockInputObservation.destinationReachableVia]); on Červený Újezd the #1278/#1299 rules (the own
 * exit is listed one section ahead) already decide every pick, so the differential observation of
 * [PrahaEntryPlatformExitTest], run on the Cerveny Ujezd fixture under the synchronous rule-based
 * dispatcher, must find zero picks changed by the reachable set and zero wrong-out reports — the
 * "other examples pinned flat" guarantee, by test instead of by reasoning.
 *
 * The loop is built by hand for the same reason the Praha test's is: the differential listener must
 * wrap the dispatcher step, which the generic run helpers do not compose ([SteeredPickObserver]'s
 * helper keeps the ordering in one place).
 */
@Tag("integration-test")
class CervenyUjezdSteeredPickFlatTest : KoinTestBase() {
	private companion object {
		private val logger = KotlinLogging.logger {}

		const val END_TIME: Long = 600L
		const val TRAINS: Int = 3
	}

	@ParameterizedTest(name = "seed {0}")
	@ValueSource(longs = [1163L, 0L])
	fun `the platform steer changes no pick and nothing else moves`(seed: Long) {
		val ctx: DefaultSimulationContext =
			CommonTestFixtures.parseSimulationContext(NetworkResources.CERVENY_UJEZD_XML, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val loop = ShuntingLoop(ctx, END_TIME, seed = seed, maxTrains = TRAINS)
		wireSynchronousDispatcher(ctx, loop)
		val dispatcherStep = checkNotNull(loop.controlStepListener)
		val observer = SteeredPickObserver()
		loop.controlStepListener = observer.controlStepListener(loop, dispatcherStep)
		ctx.setMainProcess(loop)

		var wrongOutReports = 0
		runSampled(ctx) { message ->
			if (message?.endsWith(WRONG_OUT_TRAIN_REPORT_FRAGMENT) == true) wrongOutReports++
		}

		logger.info {
			"Cerveny Ujezd steer-flat run: seed=$seed entered=${loop.getTrainsEntered()} " +
				"exited=${loop.getTrainsExited()} wrongOut=$wrongOutReports " +
				"inputsChecked=${observer.inputsChecked} steeredPicks=${observer.steeredPicks}"
		}
		assertThat(loop.getTrainsEntered(), name = "trains entered").isEqualTo(TRAINS)
		assertThat(loop.getTrainsExited(), name = "trains exited").isEqualTo(TRAINS)
		assertThat(wrongOutReports, name = "'ends in wrong out' reports").isEqualTo(0)
		assertThat(observer.inputsChecked, name = "observations checked").isGreaterThan(0)
		assertThat(observer.steeredPicks, name = "picks changed by the reachable set").isZero()
	}
}
