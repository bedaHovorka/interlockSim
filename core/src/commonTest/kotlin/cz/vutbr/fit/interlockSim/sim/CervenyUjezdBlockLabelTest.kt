/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1279: every block label of the Cerveny Ujezd run is readable.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.runSampled
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Runs the seeded Cerveny Ujezd scenario (the [CervenyUjezdRunTest] setup: [ShuntingLoop] under
 * the synchronous rule-based dispatcher) and collects the block label of every `enter block` /
 * `leave block` TRAIN_EVENTS message.
 *
 * [blockLabel] prints `unknown` only for an unnamed block whose two end separators are both
 * unnamed. The throat blocks of both switch ladders run switch to switch, so an unnamed
 * `RailSwitch` in `cerveny_ujezd.xml` shows up here as an `unknown` label (#1279).
 */
class CervenyUjezdBlockLabelTest : CommonKoinTestBase() {
	private companion object {
		private const val SEED: Long = 1163L
		private const val TRAINS: Int = 3
		private const val END_TIME: Long = 600L

		/** The fallback [blockLabel] prints when neither the block nor its ends carry a name. */
		private const val UNKNOWN_LABEL: String = "unknown"

		/** `Train` reports `enter block <label>` and `leave block <label>`; the label is the last token. */
		private val BLOCK_EVENT_REGEX = Regex("""\b(?:enter|leave) block (\S+)$""")
	}

	@Test
	fun everyBlockLabelOfTheSeededRunDerivesFromNames() {
		val ctx: DefaultSimulationContext =
			CommonTestFixtures.parseSimulationContext(NetworkResources.CERVENY_UJEZD_XML, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val loop = ShuntingLoop(ctx, END_TIME, seed = SEED, maxTrains = TRAINS)
		wireSynchronousDispatcher(ctx, loop)
		ctx.setMainProcess(loop)

		val labels = mutableListOf<String>()
		runSampled(ctx) { message ->
			message?.let { BLOCK_EVENT_REGEX.find(it) }?.let { labels += it.groupValues[1] }
		}

		assertThat(labels, name = "block labels in TRAIN_EVENTS").isNotEmpty()
		assertThat(labels, name = "block labels in TRAIN_EVENTS").doesNotContain(UNKNOWN_LABEL)
	}
}
