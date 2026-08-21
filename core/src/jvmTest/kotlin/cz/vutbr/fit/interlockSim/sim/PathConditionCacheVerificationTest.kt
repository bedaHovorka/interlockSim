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
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import cz.ksimulantenbande.kdisco.Condition
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.DefaultTrainNavigationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Proves the read-set claim behind the Issue #931 f2 path-available cache, on a running simulation.
 *
 * ## What is being proved
 *
 * The cache lets a waiting train skip a re-test while [PathReservationRegistry.mutationEpoch] is
 * unchanged. That is sound only if `findReservedPathForTrain` reads nothing outside the registry.
 * Reading the code says it does not: `getPathInfo` and `getOwner` are its only mutable inputs,
 * `PathInfo` is immutable, `TopologyNavigator.getNextTrackBlock` reads the static grid and ignores
 * switch configuration, and no block occupant is consulted anywhere on the path. Reading the code
 * is not proof.
 *
 * [DefaultTrainNavigationService.verifyEveryEvaluation] turns the claim into an assertion: every
 * cache hit recomputes and compares, and throws on any disagreement. If a registry mutation ever
 * forgets to bump the epoch, this test fails and names the train.
 *
 * ## Why the scenario is forced
 *
 * A healthy run never reaches the condition at all: the dispatcher has the route set before the
 * train arrives, so the train never parks on `waitUntil(env.createPathAvailableCondition(...))`
 * and never waits at a STOP signal. Measured with a probe — a 400 s `ThreeTrainLoop` mutated the
 * registry 33 times and tested the condition zero times. So the wait is induced the same way
 * [Issue905OriginAbandonRegressionTest] induces it: a decorator answers each train's **first**
 * origin query with [PathResult.OwnershipConflict]. That is exactly the production shape this
 * cache exists for — the train parks at its entry `InOut` and is woken by the dispatcher's next
 * reservation.
 *
 * The decorator routes the condition itself to the verifying service, so what the parked train
 * waits on is the real cached implementation.
 *
 * The test cannot pass vacuously: it asserts the conflict was really injected, that trains still
 * exited, and that the condition was really tested.
 *
 * @since Issue #931 f2 (Wave 3 — per-event pathfind churn)
 */
@DisplayName("Issue #931 f2: the path-available cache never disagrees with a fresh evaluation")
@Tag("integration-test")
class PathConditionCacheVerificationTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	@DisplayName("a parked train's wake-up condition never returns a stale cached answer")
	fun parkedTrainNeverSeesAStaleCachedAnswer() {
		val context =
			TestFixtures.loadShuntingXml().use { xmlStream ->
				val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
				simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
			}
		// Must precede loop construction — see ShuntingLoopRegressionTest.
		context.getInOuts()

		val registry: PathReservationRegistry = context.scope.get()
		val verifying = DefaultTrainNavigationService(context, registry, verifyEveryEvaluation = true)
		val conflicts = AtomicInteger(0)
		val stallingContext =
			NavigationDecoratingContext(context, OriginStallingNavigation(verifying, conflicts))

		val loop = ShuntingLoop(stallingContext, END_TIME)
		wireSynchronousDispatcher(stallingContext, loop)
		context.setMainProcess(loop)

		// A stale cached answer throws out of the simulation thread from verifyCachedAnswer.
		context.run()

		val stats = verifying.evaluationStats()
		logger.info {
			"conflictsInjected=${conflicts.get()}, entered=${loop.getTrainsEntered()}, " +
				"exited=${loop.getTrainsExited()}, conditionTests=${stats.conditionTests}, " +
				"realEvaluations=${stats.realEvaluations}"
		}

		// Not vacuous: the wait was really induced...
		assertThat(conflicts.get(), "injected origin conflicts").isGreaterThan(0)
		// ...trains still completed their journeys...
		assertThat(loop.getTrainsExited(), "trains exited").isGreaterThan(0)
		assertThat(loop.getTrainsEntered(), "trains entered")
			.isGreaterThanOrEqualTo(loop.getTrainsExited())
		// ...and the cached condition really was tested.
		assertThat(stats.conditionTests, "condition tests").isNotNull()
		assertThat(stats.conditionTests!!, "condition tests").isGreaterThan(0L)
	}

	/**
	 * Answers each train's first query at its origin `InOut` with [PathResult.OwnershipConflict],
	 * then delegates everything to [delegate].
	 *
	 * A hand-written wrapper rather than a MockK `spyk`: a parked train queries after every event
	 * and every integration step, and spying that has been measured exhausting a 512 MB test worker
	 * with `OutOfMemoryError` (see [NavigationDecoratingContext]).
	 *
	 * [createPathAvailableCondition] delegates rather than inheriting the interface default, so the
	 * parked train waits on the **cached** implementation under test — inheriting the default would
	 * silently exercise the un-cached path and the test would prove nothing.
	 */
	private class OriginStallingNavigation(
		private val delegate: TrainNavigationService,
		private val conflicts: AtomicInteger
	) : TrainNavigationService {
		private val firstOriginQuerySeen = ConcurrentHashMap<String, Boolean>()

		override fun findReservedPathForTrain(
			trainId: String,
			separator: PathSeparator
		): PathResult {
			if (separator is DynamicInOut &&
				firstOriginQuerySeen.putIfAbsent("$trainId@${separator.name}", true) == null
			) {
				conflicts.incrementAndGet()
				return PathResult.OwnershipConflict
			}
			return delegate.findReservedPathForTrain(trainId, separator)
		}

		override fun isPathReservedForTrain(
			trainId: String,
			separator: PathSeparator
		): Boolean = delegate.isPathReservedForTrain(trainId, separator)

		override fun reservedSeparatorsAhead(
			trainId: String,
			separator: PathSeparator,
			limit: Int
		): List<OrientedPathSeparator> = delegate.reservedSeparatorsAhead(trainId, separator, limit)

		override fun createPathAvailableCondition(
			trainId: String,
			separator: PathSeparator
		): Condition = delegate.createPathAvailableCondition(trainId, separator)
	}

	private companion object {
		/** Long enough for several trains to enter, park, be released and leave. */
		private const val END_TIME = 120L
	}
}
