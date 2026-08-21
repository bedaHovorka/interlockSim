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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.InputStream

/**
 * Tests for the epoch-cached path-available condition (Issue #931 f2).
 *
 * kDisco re-tests a `waitUntil` predicate after every discrete event and every integration step,
 * so a parked train ran 10,800-40,490 full path evaluations per 333 s GUI run. The cache answers a
 * re-test from memory while [PathReservationRegistry.mutationEpoch] is unchanged.
 *
 * Two things have to hold, and only one of them is about speed:
 *
 * - The cache must **not** survive a reservation change, or a train sleeps through its own wake-up.
 * - Each real evaluation must return a **fresh** path object, because `Train.Site.separatorAction`
 *   calls `removeFirst()` on the path it is handed.
 *
 * Evaluation counts come from [DefaultTrainNavigationService.evaluationStats], which is the same
 * instrumentation the run-end log line reports — deliberately not a MockK `spyk`, because spying
 * this interface records one call per evaluation and has been measured killing a 512 MB test
 * worker with `OutOfMemoryError` (see `NavigationDecoratingContext`).
 *
 * @since Issue #931 f2 (Wave 3 — per-event pathfind churn)
 */
class PathAvailableConditionCacheTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var context: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: DefaultTrainNavigationService
	private lateinit var pathService: PathReservationService
	private lateinit var origin: DynamicInOut
	private lateinit var target: DynamicInOut

	@BeforeEach
	fun setUp() {
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")
		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		registry = context.scope.get()
		service = DefaultTrainNavigationService(context, registry)
		pathService = context.getRoutingServices().getPathReservationService()
		// The two vyhybna InOuts, by grid position — the same anchors TrainNavigationServiceTest uses.
		origin = context.getRailWayNetGrid().getCellAt(ORIGIN_X, IN_OUT_Y) as DynamicInOut
		target = context.getRailWayNetGrid().getCellAt(TARGET_X, IN_OUT_Y) as DynamicInOut
	}

	/** Reserves the full route for [TRAIN] through the real reservation service. */
	private fun reserveRoute() {
		pathService.reservePath(TRAIN, origin, target)
	}

	@Test
	@DisplayName("the first test always evaluates")
	fun firstTestEvaluates() {
		service.createPathAvailableCondition(TRAIN, origin).test()

		assertThat(service.evaluationStats().realEvaluations, "path evaluations").isEqualTo(1L)
	}

	@Test
	@DisplayName("repeated tests at an unchanged epoch do not evaluate again")
	fun repeatedTestsAtSameEpochDoNotEvaluate() {
		val condition = service.createPathAvailableCondition(TRAIN, origin)

		repeat(TEST_REPEATS) { condition.test() }

		val stats = service.evaluationStats()
		assertThat(stats.conditionTests, "condition tests").isEqualTo(TEST_REPEATS.toLong())
		assertThat(stats.realEvaluations, "path evaluations for $TEST_REPEATS tests").isEqualTo(1L)
	}

	@Test
	@DisplayName("a registry mutation makes the next test evaluate again")
	fun mutationForcesReEvaluation() {
		val condition = service.createPathAvailableCondition(TRAIN, origin)
		repeat(TEST_REPEATS) { condition.test() }

		registry.clear()
		repeat(TEST_REPEATS) { condition.test() }

		assertThat(service.evaluationStats().realEvaluations, "path evaluations after one mutation")
			.isEqualTo(2L)
	}

	/**
	 * The transition that matters most, and the one an event-driven design would miss: a route
	 * extension registers a `PathInfo` over blocks the train already holds and emits no block event
	 * at all. If the cache did not invalidate on it, the train would never wake up.
	 */
	@Test
	@DisplayName("registering a PathInfo alone flips the condition from false to true")
	fun pathInfoRegistrationFlipsTheAnswer() {
		val condition = service.createPathAvailableCondition(TRAIN, origin)
		assertThat(condition.test(), "before any reservation").isFalse()

		reserveRoute()

		assertThat(condition.test(), "after the route is reserved").isTrue()
	}

	@Test
	@DisplayName("releasing the route flips the condition from true back to false")
	fun releaseFlipsTheAnswerBack() {
		reserveRoute()
		val condition = service.createPathAvailableCondition(TRAIN, origin)
		assertThat(condition.test(), "with the route reserved").isTrue()

		registry.unregister(TRAIN)

		assertThat(condition.test(), "after the route is released").isFalse()
	}

	/** One cache per condition instance, so two waiting trains cannot answer each other's question. */
	@Test
	@DisplayName("conditions for different trains do not share cache state")
	fun conditionsDoNotShareState() {
		val first = service.createPathAvailableCondition(TRAIN, origin)
		val second = service.createPathAvailableCondition(OTHER_TRAIN, origin)

		first.test()
		second.test()

		assertThat(service.evaluationStats().realEvaluations, "path evaluations for two conditions")
			.isEqualTo(2L)
	}

	/**
	 * `Train.Site.separatorAction` mutates the path it is handed (`removeFirst()`), which is safe
	 * only because every evaluation builds a new one. Caching the [PathResult] instead of the
	 * boolean would hand the same object to two callers and corrupt it.
	 */
	@Test
	@DisplayName("each real evaluation returns a fresh path object")
	fun eachEvaluationReturnsAFreshPath() {
		reserveRoute()

		val first = service.findReservedPathForTrain(TRAIN, origin) as PathResult.Available
		val second = service.findReservedPathForTrain(TRAIN, origin) as PathResult.Available

		assertThat(second.path, "path from the second evaluation").isNotSameInstanceAs(first.path)
	}

	@Test
	@DisplayName("evaluationStats reports a cache hit rate once tests outnumber evaluations")
	fun evaluationStatsReportsHitRate() {
		val condition = service.createPathAvailableCondition(TRAIN, origin)
		repeat(TEST_REPEATS) { condition.test() }

		val stats = service.evaluationStats()

		assertThat(stats.cacheHitRate, "cache hit rate").isNotNull()
		assertThat(stats.cacheHitRate, "cache hit rate")
			.isEqualTo((TEST_REPEATS - 1).toDouble() / TEST_REPEATS.toDouble())
	}

	/**
	 * A service that keeps no counters is *not measuring*, which the interface default expresses as
	 * absent rather than zero — the same convention `RailwayOutcome` follows. The double below also
	 * proves the interface's **default** [TrainNavigationService.createPathAvailableCondition] still
	 * works for a test double that knows nothing about caching, which is what
	 * `NavigationDecoratingContext` and `RouteHidingContext` rely on.
	 */
	@Test
	@DisplayName("an uninstrumented service reports absent stats and still builds a working condition")
	fun uninstrumentedServiceReportsAbsentStats() {
		val plain = CountingNavigationService(service)

		val stats = plain.evaluationStats()
		val condition = plain.createPathAvailableCondition(TRAIN, origin)
		repeat(TEST_REPEATS) { condition.test() }

		assertThat(stats.conditionTests, "condition tests").isNull()
		assertThat(stats.realEvaluations, "real evaluations").isNull()
		assertThat(stats.cacheHitRate, "cache hit rate").isNull()
		// The default body evaluates every time; that is exactly why the override exists.
		assertThat(plain.calls, "evaluations through the uncached default").isEqualTo(TEST_REPEATS)
	}

	/**
	 * The debug switch that proves the read-set claim: it recomputes on every cache hit and fails
	 * loudly if the cached answer disagrees. Under a correct epoch it must simply agree, across a
	 * whole reserve-then-release lifecycle.
	 */
	@Test
	@DisplayName("verifyEveryEvaluation finds no disagreement across a reservation lifecycle")
	fun verificationModeFindsNoDisagreement() {
		val verifying = DefaultTrainNavigationService(context, registry, verifyEveryEvaluation = true)
		val condition = verifying.createPathAvailableCondition(TRAIN, origin)

		repeat(TEST_REPEATS) { condition.test() }
		reserveRoute()
		repeat(TEST_REPEATS) { condition.test() }
		registry.unregister(TRAIN)
		repeat(TEST_REPEATS) { condition.test() }

		assertThat(condition.test(), "final answer after release").isFalse()
	}

	/** Counts [findReservedPathForTrain] calls and inherits every interface default. */
	private class CountingNavigationService(
		private val delegate: TrainNavigationService
	) : TrainNavigationService {
		var calls = 0
			private set

		override fun findReservedPathForTrain(
			trainId: String,
			separator: PathSeparator
		): PathResult {
			calls++
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
	}

	private companion object {
		private const val TRAIN = "train1"
		private const val OTHER_TRAIN = "train2"

		/** Grid row shared by both `vyhybna.xml` InOuts. */
		private const val IN_OUT_Y = 8
		private const val ORIGIN_X = 11
		private const val TARGET_X = 30

		/** Stands in for the many re-tests kDisco fires between two reservation changes. */
		private const val TEST_REPEATS = 50
	}
}
