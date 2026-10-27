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
import assertk.assertions.containsExactly
import assertk.assertions.each
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Reservation expiry phase 2, Issue #1220 (SP12.8): the stalled RESERVED tail of a train that
 * stands on part of its holding is freed through [RouteReleaser.releasePathDetailed] once the train
 * has made no progress for the TTL in simulation time; the blocks it stands on never expire.
 *
 * Progress is the traffic-simulation-expert's snapshot (ruling D1/D2 on #1220): the occupied block
 * set, the RESERVED block set and the occupant's `distanceToSemaphore()` reading. The stall clock
 * restarts whenever any of them changes, the reading by more than 0.01 m. A [ProgressStub] stands in
 * for the train, so each scenario sets the reading by hand. The stall rule alone, without kDisco or a
 * release, is pinned in [TailStallTrackerTest]; this class pins it through the release.
 *
 * The loop tick runs every 2.0 simulated seconds in `ShuntingLoop`, so [ticks] calls the expiry at
 * that cadence. No wall-clock time is involved anywhere.
 */
class ReservationExpiryOccupyingTrainTest : ReservationExpiryTestBase() {
	/** A train on the track whose progress reading the scenario moves by hand. */
	private class ProgressStub(
		override val name: String,
		var reading: Double = START_READING
	) : TrackOccupant {
		override fun distanceToSemaphore(): Double = reading

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}

	private lateinit var inA: DynamicPathSeparator
	private lateinit var inB: DynamicPathSeparator
	private lateinit var kA: DynamicTrackBlock
	private lateinit var zA: DynamicRailSemaphore
	private val releasedEvents = mutableListOf<DynamicTrackBlock>()

	@BeforeEach
	fun setUpVyhybnaHead() {
		// Every scenario runs eastbound, A -> B: kA (A-zA) is the first block of each route.
		inA = inOutNamed("A")
		inB = inOutNamed("B")
		kA = blockBetween("A", "zA")
		zA = findSemaphoreByName("zA")
		service.addBlockOccupancyListener { event ->
			if (event.type == BlockOccupancyEventType.BLOCK_RELEASED) releasedEvents += event.block
		}
	}

	/**
	 * The loop tick at every 2.0 s from [from] through [until], each running [beforeTick] first (the
	 * train's own movement in that step) and then [tick].
	 */
	private fun ticks(
		from: Double,
		until: Double,
		beforeTick: () -> Unit = {},
		tick: () -> Unit = { service.expireStaleReservations() }
	): List<Pair<Double, () -> Unit>> =
		generateSequence(from) { it + TICK }
			.takeWhile { it <= until }
			.map { at ->
				at to {
					beforeTick()
					tick()
				}
			}.toList()

	/** [steps] at their times, in time order (a step listed first wins a tie), inside one simulation. */
	private fun runInOrder(vararg steps: List<Pair<Double, () -> Unit>>) =
		atSimTimes(*steps.flatMap { it }.sortedBy { it.first }.toTypedArray())

	/** Reserve the whole `A -> B` route for [trainId] and put [occupant] on its first block, kA. */
	private fun reserveWholeRouteStandingOnKa(
		trainId: String,
		occupant: TrackOccupant
	): List<DynamicTrackBlock> {
		val blocks = assertReservationSuccess(service.reservePath(trainId, inA, inB)).reservedBlocks
		assertThat(blocks.first()).isSameInstanceAs(kA)
		kA.enter(occupant)
		return blocks
	}

	/** The tail is still the train's: RESERVED, registered to it, nobody on it. */
	private fun assertTailHeld(
		tail: List<DynamicTrackBlock>,
		trainId: String = TRAIN
	) {
		tail.forEach { block ->
			assertThat(block.getState(), "state of $block").isEqualTo(TrackFacility.State.RESERVED)
			assertThat(registry.getOwner(block), "owner of $block").isEqualTo(trainId)
		}
	}

	/** [trainId] still stands on kA, which it still holds. */
	private fun assertStillStandingOnKa(trainId: String = TRAIN) {
		assertThat(kA.getState()).isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(registry.getOwner(kA)).isEqualTo(trainId)
	}

	/** The expiry's log lines about [trainId]'s tail that start with [prefix], at [level]. */
	private fun tailLines(
		appender: ListAppender<ILoggingEvent>,
		prefix: String,
		level: Level,
		trainId: String = TRAIN
	): List<ILoggingEvent> =
		appender.list.filter { it.level == level && it.formattedMessage.startsWith("$prefix '$trainId'") }

	/** A [DefaultReservationExpiry] over this test's registry whose release counts its calls. */
	private fun countingExpiry(calls: MutableList<String>): DefaultReservationExpiry =
		expiryWith { trainId ->
			calls += trainId
			service.releasePathDetailed(trainId)
		}

	@Test
	fun `a train moving inside one long block keeps its tail at three TTLs`() {
		val occupant = ProgressStub(TRAIN)
		lateinit var tail: List<DynamicTrackBlock>
		lateinit var proceeding: List<DynamicRailSemaphore>

		runInOrder(
			listOf(
				0.0 to {
					tail = reserveWholeRouteStandingOnKa(TRAIN, occupant).drop(1)
					proceeding = simulationContext.cellsOfType<DynamicRailSemaphore>().filter { it.signal.isAllowing() }
				}
			),
			// The block sets never change, but the reading does: the train is moving.
			ticks(0.0, 3 * TTL + TICK, beforeTick = { occupant.reading -= MOVING_STEP })
		)

		assertThat(proceeding).isNotEmpty()
		assertTailHeld(tail)
		assertStillStandingOnKa()
		// No STOP thrown in front of a moving train, and nothing released.
		assertThat(proceeding.map { it.signal.isAllowing() }).each { it.isTrue() }
		assertThat(releasedEvents).isEmpty()
		assertThat(expiry.expiredTailCount).isEqualTo(0)
	}

	@Test
	fun `a stopped train loses exactly its RESERVED tail after the TTL`() {
		lateinit var tail: List<DynamicTrackBlock>
		lateinit var freedBeforeCompletion: List<DynamicTrackBlock>
		val returned = mutableListOf<Int>()

		runInOrder(
			listOf(0.0 to { tail = reserveWholeRouteStandingOnKa(TRAIN, ProgressStub(TRAIN)).drop(1) }),
			listOf(
				0.0 to { returned += service.expireStaleReservations() },
				BELOW_TTL to {
					returned += service.expireStaleReservations()
					// Below the TTL nothing happens.
					assertTailHeld(tail)
					assertThat(zA.signal.isAllowing()).isTrue()
				},
				// At the TTL the release drops the signals and keeps the approach-locked block behind
				// the proceed aspect at zA; past its booking window the rest goes.
				TTL to { freedBeforeCompletion = expireAtTtlDeferred(returned) },
				PAST_TTL to { returned += service.expireStaleReservations() },
				// Nothing is left to release: later ticks change nothing and count nothing.
				(PAST_TTL + TICK) to { returned += service.expireStaleReservations() }
			)
		)

		assertOnlyTailFreedKaKept(tail, returned)
		assertTailExpiryAnnounced(freedBefore = freedBeforeCompletion.size)
	}

	/**
	 * The loop tick at the TTL, for a train stalled for exactly the TTL: the release has run (its first
	 * step resets the signals) but deferred on the approach lock, so nothing is announced yet (Issue
	 * #1243). Returns the blocks released so far.
	 */
	private fun expireAtTtlDeferred(returned: MutableList<Int>): List<DynamicTrackBlock> {
		returned += service.expireStaleReservations()
		assertThat(zA.signal).isEqualTo(Signal.STOP)
		assertThat(expiredEvents()).isEmpty()
		return releasedEvents.toList()
	}

	/** Only [tail] went, once, by one counted expiry; the train keeps kA, its PathInfo cut back to it. */
	private fun assertOnlyTailFreedKaKept(
		tail: List<DynamicTrackBlock>,
		returned: List<Int>
	) {
		// The occupied head stays, with its PathInfo cut back to the block the train stands on ...
		assertStillStandingOnKa()
		assertThat(registry.getBlocks(TRAIN)).containsExactly(kA)
		assertThat(registry.getPathInfo(TRAIN)).isNotNull().transform { it.target }.isSameInstanceAs(zA)
		// ... the tail is free and unregistered, each block announced once, the expiry counted once ...
		assertBlocksFreeAndUnowned(tail)
		assertThat(releasedEvents.sortedBy { tail.indexOf(it) }).containsExactly(*tail.toTypedArray())
		assertThat(returned.sum()).isEqualTo(1)
		assertThat(expiry.expiredTailCount).isEqualTo(1)
		// ... and the signal at the head of the freed tail reads STOP.
		assertThat(zA.signal).isEqualTo(Signal.STOP)
	}

	/**
	 * The completed tail expiry is announced once, at PAST_TTL, naming the tail case (Issue #1243): one
	 * event, one count. [freedBefore] blocks went out before the completing call.
	 */
	private fun assertTailExpiryAnnounced(freedBefore: Int) {
		val event = expiredEvents().single()
		assertThat(event.trainId).isEqualTo(TRAIN)
		assertThat(event.kind).isEqualTo(ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL)
		assertThat(event.keptBlocks).containsExactly(kA)
		// Only what the completing PAST_TTL call freed, in release order: the TTL call's blocks (if any)
		// went out as BlockReleased already.
		assertThat(event.releasedBlocks).isNotEmpty()
		assertThat(event.releasedBlocks).isEqualTo(releasedEvents.drop(freedBefore))
		assertThat(event.stalledForSimSeconds).isCloseTo(PAST_TTL, EVENT_TOLERANCE)
		assertThat(event.ttlSimSeconds).isEqualTo(TTL)
		assertThat(event.time).isEqualTo(PAST_TTL)
		assertThat(expiredEvents().size).isEqualTo(expiry.expiredTailCount)
	}

	@Test
	fun `creep of less than one centimetre within the TTL counts as stalled`() {
		val occupant = ProgressStub(TRAIN)
		lateinit var tail: List<DynamicTrackBlock>

		runInOrder(
			listOf(0.0 to { tail = reserveWholeRouteStandingOnKa(TRAIN, occupant).drop(1) }),
			// 30 ticks of 0.1 mm each: 3 mm by the TTL, the Issue #797 asymptotic approach.
			ticks(0.0, TTL + 2 * TICK, beforeTick = { occupant.reading -= CREEP_STEP })
		)

		assertBlocksFreeAndUnowned(tail)
		assertStillStandingOnKa()
		assertThat(expiry.expiredTailCount).isEqualTo(1)
	}

	@Test
	fun `creep that adds up to more than one centimetre is progress`() {
		// The threshold is measured from the reading the clock started at, not from the previous
		// tick: 4 mm per tick is under 1 cm each time, but passes it on the third tick.
		val occupant = ProgressStub(TRAIN)
		lateinit var tail: List<DynamicTrackBlock>

		runInOrder(
			listOf(0.0 to { tail = reserveWholeRouteStandingOnKa(TRAIN, occupant).drop(1) }),
			ticks(0.0, 3 * TTL + TICK, beforeTick = { occupant.reading -= SLOW_STEP })
		)

		assertTailHeld(tail)
		assertThat(releasedEvents).isEmpty()
		assertThat(expiry.expiredTailCount).isEqualTo(0)
	}

	@Test
	fun `the train clearing a block it stood on is progress`() {
		val occupant = ProgressStub(TRAIN)
		lateinit var tail: List<DynamicTrackBlock>
		lateinit var proceeding: List<DynamicRailSemaphore>

		runInOrder(
			listOf(
				0.0 to {
					val blocks = reserveWholeRouteStandingOnKa(TRAIN, occupant)
					// The front has already entered the second block: the train stands on two.
					blocks[1].enter(occupant)
					tail = blocks.drop(2)
					proceeding = simulationContext.cellsOfType<DynamicRailSemaphore>().filter { it.signal.isAllowing() }
				},
				// The rear clears kA 40 s in; the stub's reading does not move, the RESERVED set does
				// not change, only the occupied set does -- and that restarts the clock.
				(CLEARS_AT - 1.0) to { kA.leave(occupant) },
				// A TTL after t=0 (and past the approach-lock window that would follow), not after t=40.
				(TTL + 5.0) to {
					assertTailHeld(tail)
					assertThat(proceeding.map { it.signal.isAllowing() }).each { it.isTrue() }
				}
			),
			ticks(0.0, TTL + 6.0)
		)

		assertThat(proceeding).isNotEmpty()
		assertThat(expiry.expiredTailCount).isEqualTo(0)
	}

	@Test
	fun `a route extension restarts the stall clock`() {
		val doB1 = findSemaphoreByName("doB1")
		lateinit var firstLeg: List<DynamicTrackBlock>
		lateinit var extension: List<DynamicTrackBlock>

		runInOrder(
			listOf(
				0.0 to {
					assertReservationSuccess(service.reservePath(TRAIN, inA, zA))
					kA.enter(ProgressStub(TRAIN))
					firstLeg = assertReservationSuccess(service.reservePath(TRAIN, zA, doB1)).reservedBlocks
				},
				// A new claim 50 s in: the RESERVED set changes, so the next tick restarts the clock.
				(EXTENSION_AT - 1.0) to {
					extension = assertReservationSuccess(service.reservePath(TRAIN, doB1, inB)).reservedBlocks
				},
				// 70 s after the first claim, but only 20 s after the extension: kept.
				(EXTENSION_AT + 20.0 + 1.0) to { assertTailHeld(firstLeg + extension) },
				// One tick short of a TTL after the tick that saw the extension: still kept, signals clear.
				(EXTENSION_AT + TTL - TICK + 0.5) to {
					assertTailHeld(firstLeg + extension)
					assertThat(zA.signal.isAllowing()).isTrue()
				}
			),
			ticks(0.0, EXTENSION_AT + TTL + 2 * TICK)
		)

		assertBlocksFreeAndUnowned(firstLeg + extension)
		assertStillStandingOnKa()
		assertThat(expiry.expiredTailCount).isEqualTo(1)
	}

	@Test
	fun `a #943 waiter holding only the blocks it stands on keeps them, with no release call and no log line`() {
		withExpiryLog { appender ->
			val releaseCalls = mutableListOf<String>()
			val counting = countingExpiry(releaseCalls)

			runInOrder(
				listOf(
					0.0 to {
						// It has consumed its leg up to the STOP signal it waits at: kA is all it holds.
						assertReservationSuccess(service.reservePath(TRAIN, inA, zA))
						kA.enter(ProgressStub(TRAIN))
						zA.signal = Signal.STOP
					}
				),
				ticks(0.0, 3 * TTL + TICK, tick = { counting.expireStale(null) })
			)

			assertThat(registry.getBlocks(TRAIN)).containsExactly(kA)
			assertStillStandingOnKa()
			assertThat(releasedEvents).isEmpty()
			// An empty tail is skipped silently (ruling D5): no release call, no log line at all.
			assertThat(releaseCalls).isEmpty()
			assertThat(appender.list.filter { it.formattedMessage.contains("'$TRAIN'") }).isEmpty()
			assertThat(counting.expiredTailCount).isEqualTo(0)
			// ... and no event (Issue #1243).
			assertThat(expiredEvents()).isEmpty()
		}
	}

	@Test
	fun `the PR #940 waiter loses the tail that does not reach a forward-facing separator`() {
		val doA1 = findSemaphoreByName("doA1")
		lateinit var tail: List<DynamicTrackBlock>

		runInOrder(
			listOf(
				0.0 to {
					assertReservationSuccess(service.reservePath(TRAIN, inA, zA))
					kA.enter(ProgressStub(TRAIN))
					tail = holdRearFacingTail(zA, doA1)
				}
			),
			ticks(0.0, TTL + 2 * TICK)
		)

		assertThat(tail).isNotEmpty()
		assertBlocksFreeAndUnowned(tail)
		assertThat(registry.getBlocks(TRAIN)).containsExactly(kA)
		assertThat(registry.getPathInfo(TRAIN)).isNotNull().transform { it.target }.isSameInstanceAs(zA)
		assertThat(expiry.expiredTailCount).isEqualTo(1)
	}

	/**
	 * Extend [TRAIN]'s holding from [zA] to the rear-facing [doA1] by hand, as `reservePath` granted
	 * it before the Issue #1188 / #1200 route-end guards: doA1 faces west, so for this eastbound
	 * train it is a terminus navigation cannot build a leg into, and the train waits at zA for an
	 * extension that never comes (the PR #940 / #944 shape). The kernel no longer grants such a
	 * route, but a tail that cannot be used must still be freed (ruling D6).
	 */
	private fun holdRearFacingTail(
		zA: DynamicRailSemaphore,
		doA1: DynamicRailSemaphore
	): List<DynamicTrackBlock> {
		val tail = listOf(blockBetween("zA", "vA"), blockBetween("vA", "doA1"))
		tail[0].setUpPath(zA, TRAIN)
		tail[1].setUpPath(findSwitchByName("vA"), TRAIN)
		assertThat(registry.registerAtomic(TRAIN, tail)).isEqualTo(PathReservationRegistry.RegistrationResult.Success)
		assertThat(registry.registerPathInfo(TRAIN, pathInfoBuilder.buildPathInfo(zA, doA1, tail)))
			.isInstanceOf<PathReservationRegistry.MergeOutcome.Merged>()
		return tail
	}

	@Test
	fun `a train whose PathInfo cannot be trimmed defers forever, logging INFO once and DEBUG afterwards`() {
		withExpiryAndReleaserLogs { expiryLog, releaserLog ->
			lateinit var stand: GrantedStand
			runInOrder(listOf(0.0 to { stand = grantAndStandBeforeSwitchVa() }), ticks(0.0, TTL + 10 * TICK))
			assertTailDeferredForever(stand.held, expiryLog)
			assertAspectsAsGranted(stand.aspectsAfterGrant)
			assertReleaserRefusedOnce(releaserLog)
		}
	}

	/** What [grantAndStandBeforeSwitchVa] leaves: the granted blocks and every aspect right after the grant. */
	private data class GrantedStand(
		val held: List<DynamicTrackBlock>,
		val aspectsAfterGrant: Map<String, Signal>
	)

	/** Grants [TRAIN] the route zA -> doB1 and puts it on the route's first block, zA-vA. */
	private fun grantAndStandBeforeSwitchVa(): GrantedStand {
		val held = assertReservationSuccess(service.reservePath(TRAIN, zA, findSemaphoreByName("doB1"))).reservedBlocks
		val zAvA = blockBetween("zA", "vA")
		assertThat(held.first()).isSameInstanceAs(zAvA)
		// The train stands on the block before switch vA: the PathInfo cannot end at a switch, so
		// releasePathDetailed frees nothing however often it is asked (D4).
		zAvA.enter(ProgressStub(TRAIN))
		return GrantedStand(held, semaphoreAspects())
	}

	/** The expiry kept every block of [held]: deferred on each tick, INFO once then DEBUG, never expired. */
	private fun assertTailDeferredForever(
		held: List<DynamicTrackBlock>,
		expiryLog: ListAppender<ILoggingEvent>
	) {
		assertTailHeld(held.drop(1))
		assertThat(registry.getBlocks(TRAIN)).containsExactly(*held.toTypedArray())
		assertThat(tailLines(expiryLog, DEFERRED, Level.INFO)).hasSize(1)
		assertThat(tailLines(expiryLog, DEFERRED, Level.DEBUG).size).isGreaterThan(5)
		assertThat(tailLines(expiryLog, EXPIRED, Level.INFO)).isEmpty()
		assertThat(expiry.expiredTailCount).isEqualTo(0)
		// A refused release is a deferral for the expiry: no event over the whole run, so no
		// 2 s event storm on the #1060 shape (Issue #1243).
		assertThat(expiredEvents()).isEmpty()
	}

	/** Every aspect still reads as right after the grant, which cleared at least one proceed aspect. */
	private fun assertAspectsAsGranted(aspectsAfterGrant: Map<String, Signal>) {
		// Issue #1242: the releaser refuses BEFORE any signal reset, so the cleared aspects stand as
		// granted.
		assertThat(aspectsAfterGrant.values.filter { it.isAllowing() }, "proceed aspects to keep").isNotEmpty()
		assertThat(semaphoreAspects(), "every aspect after the deferred expiries").isEqualTo(aspectsAfterGrant)
	}

	/** Issue #1242: the releaser's refusal is one INFO line for [TRAIN], not a WARN on every call. */
	private fun assertReleaserRefusedOnce(releaserLog: ListAppender<ILoggingEvent>) {
		assertThat(releaserLog.list.filter { it.level == Level.WARN }, "releaser WARN lines").isEmpty()
		assertThat(releaserLines(releaserLog, Level.INFO, RELEASER_REFUSED, TRAIN), "releaser INFO refusal lines")
			.hasSize(1)
	}

	@Test
	fun `a second deferral episode after a completed tail expiry logs INFO again`() {
		withExpiryLog { appender ->
			lateinit var secondLeg: List<DynamicTrackBlock>

			runInOrder(
				listOf(
					0.0 to { reserveWholeRouteStandingOnKa(TRAIN, ProgressStub(TRAIN)) },
					// The first tail is gone (approach-lock deferral, then completion, as in `a stopped
					// train loses exactly its RESERVED tail after the TTL`); the stalled train is
					// granted a new leg past the proceed aspect at zA, which stalls in turn.
					(SECOND_LEG_AT - 1.0) to {
						assertThat(expiry.expiredTailCount).isEqualTo(1)
						secondLeg =
							assertReservationSuccess(service.reservePath(TRAIN, zA, findSemaphoreByName("doB1")))
								.reservedBlocks
					}
				),
				ticks(0.0, SECOND_LEG_AT + TTL + 2 * TICK)
			)

			assertBlocksFreeAndUnowned(secondLeg)
			assertStillStandingOnKa()
			assertThat(expiry.expiredTailCount).isEqualTo(2)
			// One INFO per episode. This pins the outcome: the completion clears the train's marker, and
			// so does the next call that does not attempt the train, which a new stall needs anyway.
			assertThat(tailLines(appender, DEFERRED, Level.INFO)).hasSize(2)
			assertThat(tailLines(appender, DEFERRED, Level.DEBUG)).isEmpty()
			assertThat(tailLines(appender, EXPIRED, Level.INFO)).hasSize(2)
		}
	}

	@Test
	fun `a train with no readable occupant of its own is not judged`() {
		val releaseCalls = mutableListOf<String>()
		val counting = countingExpiry(releaseCalls)
		lateinit var tail: List<DynamicTrackBlock>

		runInOrder(
			listOf(
				0.0 to {
					val blocks = assertReservationSuccess(service.reservePath(TRAIN, inA, inB)).reservedBlocks
					tail = blocks.drop(1)
					// Somebody stands on kA, but not the train that holds it: there is no reading of
					// TRAIN's own progress, so its tail is not judged at all (ruling D2).
					kA.enter(FakeTrackOccupant("intruder"))
				}
			),
			ticks(0.0, 3 * TTL + TICK, tick = { counting.expireStale(null) })
		)

		assertTailHeld(tail)
		assertThat(releaseCalls).isEmpty()
	}

	@Test
	fun `a train with only an OCCUPIED booking and no occupant is not judged`() {
		// The literal D2 case: the train is booked onto a block (OCCUPIED) but no occupant stands on
		// it yet, so there is no reading of its progress. The public block API sets the occupant and
		// the OCCUPIED state together, so the booking is a stand-in block holding exactly that state.
		var bookedOccupant: TrackOccupant? = null
		val booked =
			mockk<DynamicTrackBlock>(relaxed = true) {
				every { getState() } returns TrackFacility.State.OCCUPIED
				every { occupant } answers { bookedOccupant }
				every { trainName } returns TRAIN
			}
		val releaseCalls = mutableListOf<Double>()
		val recording =
			expiryWith {
				releaseCalls += currentSimulationTime()
				PathRelease(emptyList(), emptyList())
			}

		runInOrder(
			listOf(
				0.0 to {
					assertReservationSuccess(service.reservePath(TRAIN, zA, findSemaphoreByName("doB1")))
					assertThat(registry.registerAtomic(TRAIN, listOf(booked)))
						.isEqualTo(PathReservationRegistry.RegistrationResult.Success)
				},
				// Its occupant is on the block only after three TTLs of booking.
				(OCCUPANT_AT - 1.0) to { bookedOccupant = ProgressStub(TRAIN) }
			),
			ticks(0.0, OCCUPANT_AT + TTL + TICK, tick = { recording.expireStale(null) })
		)

		// Never judged while only booked -- no release call, and no snapshot taken then: the first
		// call that can read the occupant starts the clock, so the release comes a TTL after it.
		assertThat(releaseCalls).isNotEmpty()
		assertThat(releaseCalls.first()).isEqualTo(OCCUPANT_AT + TTL)
	}

	@Test
	fun `a train that stops standing on its holding starts a fresh clock when it is back`() {
		val occupant = ProgressStub(TRAIN)
		lateinit var tail: List<DynamicTrackBlock>

		runInOrder(
			listOf(
				0.0 to { tail = reserveWholeRouteStandingOnKa(TRAIN, occupant).drop(1) },
				// It leaves kA without entering the next block (the stub moves by hand): kA is FREE but
				// still registered, so for a while the train stands on nothing it holds ...
				(LEAVES_AT - 1.0) to { kA.leave(occupant) },
				// ... and then it is back on kA with the very same sets and reading as at t=0.
				(BACK_AT - 1.0) to {
					kA.setUpPath(inA, TRAIN)
					kA.enter(occupant)
				},
				// Past a TTL after its first sighting (and the approach-lock window that would follow),
				// but not after it came back: the old clock is gone.
				(TTL + 5.0) to {
					assertTailHeld(tail)
					assertThat(zA.signal.isAllowing()).isTrue()
				}
			),
			ticks(0.0, BACK_AT + TTL + 2 * TICK)
		)

		assertBlocksFreeAndUnowned(tail)
		assertThat(expiry.expiredTailCount).isEqualTo(1)
	}

	@Test
	fun `the requesting train's own stalled tail survives its request`() {
		lateinit var tail: List<DynamicTrackBlock>
		var refused: PathReservationService.ReservationResult? = null

		runInOrder(
			listOf(
				0.0 to {
					tail = reserveWholeRouteStandingOnKa(TRAIN, ProgressStub(TRAIN)).drop(1)
					// No proceed aspect at the head, so one release call frees the whole tail.
					zA.signal = Signal.STOP
				},
				// Stalled for more than a TTL, but the train itself is asking: exempt at the top of its
				// own request, like phase 1. The request is refused (doB2 bounds no block the train holds).
				(TTL + 1.0) to {
					refused = service.reservePath(TRAIN, findSemaphoreByName("doB2"), inB)
					assertTailHeld(tail)
				}
			),
			// The next loop tick judges it.
			ticks(0.0, TTL - TICK) + ticks(TTL + 2.0, TTL + 2.0)
		)

		assertThat(checkNotNull(refused) { "the request was never made" })
			.isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		assertBlocksFreeAndUnowned(tail)
		assertThat(expiry.expiredTailCount).isEqualTo(1)
	}

	private companion object {
		const val TRAIN = "train1"

		/** The loop's control tick: `ShuntingLoop.iteration()` runs every 2.0 simulated seconds. */
		const val TICK = 2.0

		const val START_READING = 120.0

		/** Half a metre per tick: a train moving at 0.25 m/s inside one block. */
		const val MOVING_STEP = 0.5

		/** 0.1 mm per tick: 3 mm over the TTL, well under the 0.01 m progress threshold. */
		const val CREEP_STEP = 0.0001

		/** 4 mm per tick: under 0.01 m each tick, over it within three ticks. */
		const val SLOW_STEP = 0.004

		const val EXTENSION_AT = 50.0
		const val CLEARS_AT = 40.0
		const val OCCUPANT_AT = 3 * DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS
		const val LEAVES_AT = 30.0
		const val BACK_AT = 40.0
		const val SECOND_LEG_AT = 2 * DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS

		/** The event carries the unrounded standstill; this only absorbs floating-point noise. */
		const val EVENT_TOLERANCE = 1.0e-6

		const val DEFERRED = "Reservation tail expiry deferred for"
		const val EXPIRED = "Reservation tail expired for"
	}
}
