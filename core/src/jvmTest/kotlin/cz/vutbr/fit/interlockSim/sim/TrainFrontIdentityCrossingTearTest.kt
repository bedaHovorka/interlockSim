/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Deterministic test for Issue #1030: the identity is never torn inside the crossing block.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.decoratingTrainNavigationService
import cz.vutbr.fit.interlockSim.testutil.frontMismatches
import cz.vutbr.fit.interlockSim.testutil.isWestToEastEntryEnd
import cz.vutbr.fit.interlockSim.testutil.multiTrainSpecs
import cz.vutbr.fit.interlockSim.testutil.sameStatic
import cz.vutbr.fit.interlockSim.testutil.separatorGridColumns
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Deterministic counterpart of `TrainFrontIdentityConcurrentReadHeavyTest` (Issue #1030).
 *
 * `Train.Site.actions()` changes the front's fields one statement at a time in the crossing block
 * and, in the middle of it, asks navigation for the reserved path from the separator just crossed.
 * That query runs on the simulation thread with the block half done — exactly the state an EDT read
 * can land in. Wrapping the navigation service lets this test stand in that spot and read both
 * [Train.frontIdentity] and the live getters.
 *
 * On the one-way straight line `A` → `B` (grid x grows along the route) the entry end of every
 * section is its end with the smaller grid x. Mid-crossing the live pair is torn — the section the
 * front has just traversed, entered through its *exit* end — while the identity must still describe
 * the state before the crossing: the same section, entered through its entry end, `onNext`, and the
 * previous-blocks length without the section just traversed. At every other query the identity
 * must equal the live getters. Removing the end-of-crossing publication, or moving it before the
 * mutation, leaves a stale identity behind for the queries after the last crossing, which this
 * test catches.
 *
 * The `consistentQueries > 0` witness comes from the **Tail** site's queries: the front's own
 * non-crossing (origin) query is skipped — its identity has no section yet — and its later
 * iterations consume the carried path result without querying, so on this signal-free topology
 * only the Tail's queries land in the consistent branch. A future change to the Tail's query
 * pattern would surface as a `consistentQueries = 0` failure, never as a false pass.
 */
@DisplayName("Train.frontIdentity is not torn inside the crossing block (Issue #1030)")
class TrainFrontIdentityCrossingTearTest : KoinTestBase() {
	private companion object {
		const val END_TIME: Long = 600L
		const val TRAIN_LENGTH: Double = 40.0
		const val DISTANCE_TOLERANCE: Double = 1.0e-9
	}

	private val problems = mutableListOf<String>()
	private var tornLiveQueries = 0
	private var consistentQueries = 0

	// The shared 4-block linear fixture: the one-way straight line A → Sem1..Sem3 → B, grid x
	// grows along the route, four 100 m blocks at 80 m/s, all semaphores STOP.
	private fun straightLine(): DefaultSimulationContext =
		TestTopologies.linearPathWithSemaphoreSequenceSimulation(semaphoreCount = 3).tracked()

	private fun inspect(
		columns: Map<PathSeparator, Int>,
		train: Train,
		queried: PathSeparator
	) {
		val identity = train.frontIdentity
		val idSection = identity.section ?: return
		val idEntry = identity.entrySeparator ?: return
		if (!isWestToEastEntryEnd(columns, idSection, idEntry)) {
			problems.add(
				"query from ${separatorLabel(queried)}: identity entry ${separatorLabel(idEntry)} is not the entry end"
			)
		}
		val liveSection = train.frontSection ?: return
		val liveEntry = train.trainEntrySeparator ?: return
		if (!isWestToEastEntryEnd(columns, liveSection, liveEntry)) {
			// Mid-crossing: the live pair names the exit end just crossed; the identity is pre-crossing.
			tornLiveQueries++
			val livePreviousBlocks = train.totalDistance - train.frontIntegratedPosition
			val preCrossing =
				idSection === liveSection &&
					identity.onNext &&
					sameStatic(liveEntry, queried) &&
					abs(identity.previousBlocksLength + idSection.length() - livePreviousBlocks) <= DISTANCE_TOLERANCE
			if (!preCrossing) {
				problems.add("query from ${separatorLabel(queried)}: torn live pair, identity is not pre-crossing")
			}
			return
		}
		consistentQueries++
		if (identity.frontMismatches(train).isNotEmpty()) {
			problems.add("query from ${separatorLabel(queried)}: consistent live state, but the identity is stale")
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("mid-crossing the live pair is torn but the identity is the pre-crossing state")
	fun `identity is the pre-crossing state while the crossing block runs`() {
		val ctx = straightLine()
		val columns = separatorGridColumns(ctx)
		val realNav = ctx.getRoutingServices().getTrainNavigationService()
		lateinit var loop: MultiTrainLoop
		val nav =
			decoratingTrainNavigationService(realNav) { trainId, separator ->
				loop.getApprovedTrains().firstOrNull { it.name == trainId }?.let { inspect(columns, it, separator) }
				realNav.findReservedPathForTrain(trainId, separator)
			}
		loop =
			MultiTrainLoop(
				context = NavigationDecoratingContext(ctx, nav),
				endTime = END_TIME,
				trainSpecs = multiTrainSpecs(count = 1, interval = 1.0, length = TRAIN_LENGTH)
			)
		ctx.setMainProcess(loop)
		ctx.run()

		problems.take(5).forEach { println("FRONT_IDENTITY_TEAR: $it") }
		println(
			"WITNESS: tornLiveQueries=$tornLiveQueries consistentQueries=$consistentQueries problems=${problems.size}"
		)
		assertThat(loop.getTrainsExited(), name = "the train completed its journey").isEqualTo(1)
		assertThat(problems.isEmpty(), name = "identity problems: ${problems.take(3)}").isTrue()
		assertThat(tornLiveQueries, name = "queries made inside the crossing block").isGreaterThan(0)
		assertThat(consistentQueries, name = "queries made outside the crossing block").isGreaterThan(0)
	}
}
