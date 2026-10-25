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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.each
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Kernel-side reservation expiry, Issue #1174 (SP12.8, absorbs #975): a holding of un-entered
 * blocks that has seen no new block registration for the TTL is released through
 * [RouteReleaser.releasePathDetailed]; a train that occupies any block never expires (the stalled
 * tail of an occupying train is phase 2, #1220).
 *
 * Every scenario runs inside a kDisco simulation: the TTL is read against the simulation clock the
 * registry stamps its registrations with, and the approach-lock booking window the release honours
 * is also measured in simulation time, so only a running clock can close it. No wall-clock time is
 * involved anywhere.
 */
class ReservationExpiryTest : PathReservationServiceTestBase() {
	private companion object {
		/** The configured TTL and the sim times the scenarios sit on, all derived from it. */
		private val TTL = DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS
		private val BELOW_TTL = TTL - 0.5
		private val PAST_TTL = TTL + 1.5
		private val BELOW_TWO_TTLS = 2 * TTL - 0.5
		private val TWO_TTLS = 2 * TTL
		private val PAST_TWO_TTLS = 2 * TTL + 1.5
	}

	private val expiry: DefaultPathReservationService
		get() = service as DefaultPathReservationService

	/**
	 * Run each step at its absolute simulation time, in order, inside one kDisco process. A step
	 * that throws fails the run, so assertions may sit inside the steps.
	 */
	private fun atSimTimes(vararg steps: Pair<Double, () -> Unit>) =
		runBlocking {
			var failure: Throwable? = null
			Simulation
				.create {
					Process.activate(
						object : Process() {
							override suspend fun actions() {
								for ((at, step) in steps) {
									hold(at - currentSimulationTime())
									try {
										step()
									} catch (e: Throwable) {
										failure = failure ?: e
									}
								}
							}
						}
					)
				}.run(steps.last().first + 10.0)
			failure?.let { throw it }
		}

	private fun entrySemaphoreOf(blocks: List<DynamicTrackBlock>): DynamicRailSemaphore =
		(blocks.first().reservedFrom as DynamicInOut).inSemaphore

	@Test
	fun stalledReservationExpiresAfterTtl() {
		lateinit var blocks: List<DynamicTrackBlock>
		lateinit var entry: DynamicRailSemaphore
		lateinit var clearedSemaphores: List<DynamicRailSemaphore>
		lateinit var switches: List<DynamicRailSwitch>
		val releasedEvents = mutableListOf<DynamicTrackBlock>()
		service.addBlockOccupancyListener { event ->
			if (event.type == BlockOccupancyEventType.BLOCK_RELEASED) releasedEvents += event.block
		}
		val returned = mutableListOf<Int>()

		atSimTimes(
			0.0 to {
				blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
				entry = entrySemaphoreOf(blocks)
				clearedSemaphores = simulationContext.cellsOfType<DynamicRailSemaphore>().filter { it.signal.isAllowing() }
				switches = registry.getSwitches("train1")
			},
			BELOW_TTL to {
				// Below the TTL nothing happens.
				returned += service.expireStaleReservations()
				assertStillHeld(blocks, entry)
			},
			TTL to { returned += service.expireStaleReservations() },
			PAST_TTL to { returned += service.expireStaleReservations() }
		)

		// The precondition the release checks would be vacuous without these.
		assertThat(clearedSemaphores).isNotEmpty()
		assertThat(switches).isNotEmpty()
		assertFullyReleased(blocks, entry, clearedSemaphores, switches)
		// ... and exactly one BlockReleased per block, the holding counted once.
		assertThat(releasedEvents.sortedBy { blocks.indexOf(it) }).containsExactly(*blocks.toTypedArray())
		assertThat(returned.sum()).isEqualTo(1)
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	/** Below the TTL the holding is untouched: blocks RESERVED and owned, the entry still proceeding. */
	private fun assertStillHeld(
		blocks: List<DynamicTrackBlock>,
		entry: DynamicRailSemaphore
	) {
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(registry.getOwner(block)).isEqualTo("train1")
		}
		assertThat(entry.signal.isAllowing()).isTrue()
	}

	/** At and past the TTL the whole holding went through releasePathDetailed. */
	private fun assertFullyReleased(
		blocks: List<DynamicTrackBlock>,
		entry: DynamicRailSemaphore,
		clearedSemaphores: List<DynamicRailSemaphore>,
		switches: List<DynamicRailSwitch>
	) {
		// ... blocks FREE and unregistered ...
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block)).isNull()
		}
		assertThat(service.getReservedBlocks("train1")).isEmpty()
		// ... the START signal and every aspect the route cleared back at STOP ...
		assertThat(entry.signal).isEqualTo(Signal.STOP)
		assertThat(clearedSemaphores.map { it.signal }).each { it.isEqualTo(Signal.STOP) }
		// ... the switches unlocked and unregistered, the PathInfo gone ...
		switches.forEach { switch ->
			assertThat(switch.locked).isFalse()
			assertThat(registry.getSwitchOwner(switch)).isNull()
		}
		assertThat(registry.getPathInfo("train1")).isNull()
	}

	@Test
	fun `an approach-locked holding is deferred, not forced`() {
		lateinit var blocks: List<DynamicTrackBlock>
		lateinit var entry: DynamicRailSemaphore

		atSimTimes(
			0.0 to {
				blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
				entry = entrySemaphoreOf(blocks)
			},
			TTL to {
				// A proceed aspect stands at the head: a train may be committed to it (Issue #1050).
				assertThat(entry.signal.isAllowing()).isTrue()
				assertThat(service.expireStaleReservations()).isEqualTo(0)
				// The signals drop to STOP and the tail is freed, but the head stays reserved.
				assertThat(entry.signal).isEqualTo(Signal.STOP)
				assertThat(registry.getOwner(blocks.first())).isEqualTo("train1")
				assertThat(blocks.first().getState()).isEqualTo(TrackFacility.State.RESERVED)
				blocks.drop(1).forEach { assertThat(registry.getOwner(it)).isNull() }
				// A second call at the same instant is still inside the booking window.
				assertThat(service.expireStaleReservations()).isEqualTo(0)
				assertThat(registry.getOwner(blocks.first())).isEqualTo("train1")
			},
			60.5 to {
				// The committed train books the head inside the window: it now occupies track ...
				blocks.first().enter(FakeTrackOccupant("train1"))
			},
			1000.0 to {
				// ... and an occupying train never expires.
				assertThat(service.expireStaleReservations()).isEqualTo(0)
				assertThat(registry.getOwner(blocks.first())).isEqualTo("train1")
			}
		)

		assertThat(expiry.expiredHoldingCount).isEqualTo(0)
	}

	@Test
	fun `a train standing on a block the registry does not attribute to it never expires`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val kB = blockBetween("B", "zB")
		lateinit var route: List<DynamicTrackBlock>

		atSimTimes(
			0.0 to { route = assertReservationSuccess(service.reservePath("train1", zA, doB1)).reservedBlocks },
			1.0 to {
				// The Issue #893 state: the train physically stands on kB with no registry entry for it,
				// so the registry sees only an un-entered route (see RouteCandidateScreen.footprintOf).
				occupy(kB, "train1")
				assertThat(registry.getOwner(kB)).isNull()
			},
			100.0 to {
				// The registry-relative query does condemn the holding ...
				assertThat(registry.expiredHoldings(ttl = 60.0).map { it.trainId }).containsExactly("train1")
				// ... but a train on the track is the occupying-train case, phase 2 (#1220): kept.
				assertThat(service.expireStaleReservations()).isEqualTo(0)
			}
		)

		route.forEach { block ->
			assertThat(registry.getOwner(block)).isEqualTo("train1")
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
		}
		assertThat(zA.signal.isAllowing()).isTrue()
		assertThat(expiry.expiredHoldingCount).isEqualTo(0)
	}

	@Test
	fun `a train occupying one block of its route never expires at any age`() {
		lateinit var blocks: List<DynamicTrackBlock>

		atSimTimes(
			0.0 to {
				blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
			},
			1.0 to { blocks.first().enter(FakeTrackOccupant("train1")) },
			100_000.0 to { assertThat(service.expireStaleReservations()).isEqualTo(0) }
		)

		// Out of phase 1's scope: the stalled RESERVED tail of an occupying train is #1220.
		blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
		assertThat(blocks.drop(1).map { it.getState() }).each { it.isEqualTo(TrackFacility.State.RESERVED) }
		assertThat(expiry.expiredHoldingCount).isEqualTo(0)
	}

	@Test
	fun reservationExpiryFollowsProgress() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		lateinit var firstRoute: List<DynamicTrackBlock>

		atSimTimes(
			0.0 to { firstRoute = assertReservationSuccess(service.reservePath("train1", zA, doB1)).reservedBlocks },
			50.0 to {
				// A new claim: the train extends its route before the first one is TTL old.
				assertReservationSuccess(service.reservePath("train1", doB1, inOutNamed("B")))
			},
			70.0 to {
				// The first route's blocks are 70 s old, the newest claim only 20 s: kept past the TTL.
				assertThat(service.expireStaleReservations()).isEqualTo(0)
				firstRoute.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
				assertThat(zA.signal.isAllowing()).isTrue()
			},
			(50.0 + BELOW_TTL) to {
				assertThat(service.expireStaleReservations()).isEqualTo(0)
				firstRoute.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
			},
			// TTL after the newest claim: the holding goes, through approach locking's two steps.
			(50.0 + TTL) to { service.expireStaleReservations() },
			(50.0 + PAST_TTL) to { service.expireStaleReservations() }
		)

		assertThat(service.getReservedBlocks("train1")).isEmpty()
		assertThat(zA.signal).isEqualTo(Signal.STOP)
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	@Test
	fun `the requesting train's own stale holding survives its extension request`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		lateinit var firstSegment: List<DynamicTrackBlock>
		var extension: PathReservationService.ReservationResult? = null

		atSimTimes(
			0.0 to { firstSegment = assertReservationSuccess(service.reservePath("train1", zA, doB1)).reservedBlocks },
			TTL to {
				// The first segment is TTL old, but the requesting train is exempt from the expiry at the
				// top of its own request: its new claim restarts the clock instead. Expiring it first
				// would empty its footprint and let the extension pass contiguity vacuously (ruling D8).
				extension = service.reservePath("train1", doB1, inOutNamed("B"))
				firstSegment.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
				assertThat(zA.signal.isAllowing()).isTrue()
			},
			BELOW_TWO_TTLS to {
				assertThat(service.expireStaleReservations()).isEqualTo(0)
				firstSegment.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
			},
			// TTL after the extension: the loop tick (the public call) expires the whole holding.
			TWO_TTLS to { service.expireStaleReservations() },
			PAST_TWO_TTLS to { service.expireStaleReservations() }
		)

		assertReservationSuccess(checkNotNull(extension) { "the extension was never requested" })
		assertThat(service.getReservedBlocks("train1")).isEmpty()
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	@Test
	fun `reservePathToAny exempts the requesting train's own stale holding`() {
		val zB = findSemaphoreByName("zB")
		lateinit var held: List<DynamicTrackBlock>
		val results = mutableListOf<PathReservationService.ReservationResult>()

		atSimTimes(
			0.0 to {
				held = assertReservationSuccess(service.reservePath("train1", inOut1, zB)).reservedBlocks
				// No proceed aspect at the head: a self-expiry would free the whole holding at once.
				entrySemaphoreOf(held).signal = Signal.STOP
			},
			TTL to {
				results += service.reservePathToAny("train1", zB)
				held.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
			}
		)

		assertThat(results.single()).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(expiry.expiredHoldingCount).isEqualTo(0)
	}

	@Test
	fun `the contender's single reservePath succeeds after the expiry`() {
		lateinit var blocks: List<DynamicTrackBlock>
		var contender: PathReservationService.ReservationResult? = null

		atSimTimes(
			0.0 to { blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks },
			// A kernel tick (the loop's call) at the TTL frees the tail and defers the head.
			TTL to { service.expireStaleReservations() },
			// Past the booking window, the contender's ONE call expires the rest and reserves.
			PAST_TTL to { contender = service.reservePath("train2", inOut1, inOut2) }
		)

		assertReservationSuccess(checkNotNull(contender) { "the contender never called reservePath" })
		blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train2") }
		assertThat(service.getReservedBlocks("train1")).isEmpty()
	}

	@Test
	fun `reservePath expires a stale holding at its top before it reserves`() {
		lateinit var blocks: List<DynamicTrackBlock>
		val results = mutableListOf<PathReservationService.ReservationResult>()

		atSimTimes(
			0.0 to {
				blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
				// No proceed aspect at the head, so nothing is approach-locked.
				entrySemaphoreOf(blocks).signal = Signal.STOP
			},
			59.0 to { results += service.reservePath("train2", inOut1, inOut2) },
			TTL to { results += service.reservePath("train2", inOut1, inOut2) }
		)

		assertThat(results[0]).isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		assertReservationSuccess(results[1])
		blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train2") }
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	@Test
	fun `reservePathToAny expires a stale holding at its top before it reserves`() {
		lateinit var blocks: List<DynamicTrackBlock>
		val results = mutableListOf<PathReservationService.ReservationResult>()

		atSimTimes(
			0.0 to {
				blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
				entrySemaphoreOf(blocks).signal = Signal.STOP
			},
			59.0 to { results += service.reservePathToAny("train2", inOut1) },
			TTL to { results += service.reservePathToAny("train2", inOut1) }
		)

		assertThat(results[0]).isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(results[1]).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(service.getReservedBlocks("train1")).isEmpty()
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	@Test
	fun `a throwing release is contained, logged once, and the other trains are still expired`() {
		val expiryLogger = LoggerFactory.getLogger(DefaultReservationExpiry::class.java.name) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		expiryLogger.addAppender(appender)
		val realRelease = expiry.expiryRelease
		expiry.expiryRelease = { trainId ->
			check(trainId != "train1") { "release of train1 fails under test" }
			realRelease(trainId)
		}
		lateinit var train1Blocks: List<DynamicTrackBlock>
		val returned = mutableListOf<Int>()

		try {
			atSimTimes(
				0.0 to {
					train1Blocks =
						assertReservationSuccess(
							service.reservePath("train1", findSemaphoreByName("zA"), findSemaphoreByName("doB1"))
						).reservedBlocks
					assertReservationSuccess(
						service.reservePath("train2", findSemaphoreByName("zB"), findSemaphoreByName("doA2"))
					)
				},
				TTL to { returned += service.expireStaleReservations() },
				PAST_TTL to { returned += service.expireStaleReservations() },
				63.5 to { returned += service.expireStaleReservations() }
			)
		} finally {
			expiryLogger.detachAppender(appender)
			expiry.expiryRelease = realRelease
		}

		// No crash, and train2 was expired although train1's release threw on every call ...
		assertThat(service.getReservedBlocks("train2")).isEmpty()
		assertThat(returned.sum()).isEqualTo(1)
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
		// ... train1 keeps its holding for the next call ...
		train1Blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
		// ... and the failure was logged at WARN once, not on every call.
		val warnings =
			appender.list.filter {
				it.level == Level.WARN && it.formattedMessage.startsWith("Reservation expiry of 'train1' failed")
			}
		assertThat(warnings.size).isEqualTo(1)
	}

	@Test
	fun `a failed self-request preserves the failed-expiry WARN suppression`() {
		val expiryLogger = LoggerFactory.getLogger(DefaultReservationExpiry::class.java.name) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		expiryLogger.addAppender(appender)
		val realRelease = expiry.expiryRelease
		expiry.expiryRelease = { trainId ->
			check(trainId != "train1") { "release of train1 fails under test" }
			realRelease(trainId)
		}
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val zB = findSemaphoreByName("zB")
		lateinit var blocks: List<DynamicTrackBlock>
		val results = mutableListOf<PathReservationService.ReservationResult>()

		try {
			atSimTimes(
				0.0 to {
					blocks = assertReservationSuccess(service.reservePath("train1", zA, doB1)).reservedBlocks
					// No proceed aspect at the head, so nothing is approach-locked.
					zA.signal = Signal.STOP
				},
				// First failure: logged at WARN, and the marker remembers train1.
				TTL to { service.expireStaleReservations() },
				// The requester exemption drops train1 from this call's candidate set. The holding is
				// not attempted, so the marker must survive the call. The request itself is refused:
				// zB is not contiguous with the holding train1 holds.
				PAST_TTL to {
					results += service.reservePath("train1", zB, inOutNamed("B"))
				},
				// The next loop tick releases train1 again; the failure must stay at DEBUG now.
				63.5 to { service.expireStaleReservations() }
			)
		} finally {
			expiryLogger.detachAppender(appender)
			expiry.expiryRelease = realRelease
		}

		assertThat(results.single()).isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		// The holding survived both calls: nothing was released, none of it is stale-released.
		blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
		assertThat(expiry.expiredHoldingCount).isEqualTo(0)
		// The containment holds: exactly one WARN for train1's repeated release failure.
		val warnings =
			appender.list.filter {
				it.level == Level.WARN && it.formattedMessage.startsWith("Reservation expiry of 'train1' failed")
			}
		assertThat(warnings.size).isEqualTo(1)
	}

	@Test
	fun `a train no longer stale is un-suppressed and warns fresh on a new episode`() {
		val expiryLogger = LoggerFactory.getLogger(DefaultReservationExpiry::class.java.name) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		expiryLogger.addAppender(appender)
		val realRelease = expiry.expiryRelease
		expiry.expiryRelease = { trainId ->
			check(trainId != "train1") { "release of train1 fails under test" }
			realRelease(trainId)
		}
		lateinit var blocks: List<DynamicTrackBlock>
		lateinit var firstSegment: List<DynamicTrackBlock>

		try {
			atSimTimes(
				0.0 to {
					firstSegment =
						assertReservationSuccess(
							service.reservePath("train1", findSemaphoreByName("zA"), findSemaphoreByName("doB1"))
						).reservedBlocks
					findSemaphoreByName("zA").signal = Signal.STOP
				},
				// First episode: the failure is logged at WARN, and the marker remembers train1.
				TTL to { service.expireStaleReservations() },
				// A successful extension claims new blocks, so the whole holding's newest claim is
				// fresh and the holding leaves the candidate set; the next loop tick (a call where
				// train1 is not the requester) clears the marker.
				PAST_TTL to {
					blocks = firstSegment +
						assertReservationSuccess(
							service.reservePath("train1", findSemaphoreByName("doB1"), inOutNamed("B"))
						).reservedBlocks
				},
				62.0 to { service.expireStaleReservations() },
				// A TTL after the extension claim: the holding is stale again and fails again --
				// with a fresh WARN, because the marker was cleared.
				PAST_TWO_TTLS to { service.expireStaleReservations() }
			)
		} finally {
			expiryLogger.detachAppender(appender)
			expiry.expiryRelease = realRelease
		}

		blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
		assertThat(expiry.expiredHoldingCount).isEqualTo(0)
		val warnings =
			appender.list.filter {
				it.level == Level.WARN && it.formattedMessage.startsWith("Reservation expiry of 'train1' failed")
			}
		// One WARN per episode -- the new episode was not silenced by the old marker.
		assertThat(warnings.size).isEqualTo(2)
	}

	@Test
	fun `the next-semaphore query and reservation never expire a stale holding`() {
		lateinit var blocks: List<DynamicTrackBlock>

		atSimTimes(
			0.0 to {
				blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
				// No proceed aspect at the head: any expiry would free the whole holding at once.
				entrySemaphoreOf(blocks).signal = Signal.STOP
			},
			100.0 to {
				val next = checkNotNull(navigator.getNextTrackSection(inOut2, null))
				service.isPathToAnyNextSemaphoreAvailable(inOut2, next)
				service.reservePathToAnyNextSemaphore("train2", inOut2, next)
				service.reservePathToAnyNextSemaphore("train2", inOut2 as OrientedPathSeparator)
			}
		)

		blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
		assertThat(expiry.expiredHoldingCount).isEqualTo(0)
	}

	@Test
	fun `the TTL must be positive`() {
		assertFailure {
			DefaultPathReservationService(navigator, environment, registry, pathInfoBuilder, routeFinder, 0.0)
		}.isInstanceOf<IllegalArgumentException>()
	}
}
