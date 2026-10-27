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
import org.junit.jupiter.api.Test

/**
 * Kernel-side reservation expiry, Issue #1174 (SP12.8, absorbs #975): a holding of un-entered
 * blocks that has seen no new block registration for the TTL is released through
 * [RouteReleaser.releasePathDetailed]; the blocks a train occupies never expire (the stalled
 * tail of an occupying train is phase 2, #1220, pinned by [ReservationExpiryOccupyingTrainTest]).
 *
 * Every scenario runs inside a kDisco simulation: the TTL is read against the simulation clock the
 * registry stamps its registrations with, and the approach-lock booking window the release honours
 * is also measured in simulation time, so only a running clock can close it. No wall-clock time is
 * involved anywhere.
 */
class ReservationExpiryTest : ReservationExpiryTestBase() {
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
		// ... blocks FREE and unregistered (the shared whole-route end state) ...
		assertBlocksFreeAndUnowned(blocks)
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
			(TTL + 0.5) to {
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
				assertThat(registry.expiredHoldings(ttl = TTL).map { it.trainId }).containsExactly("train1")
				// ... but a train on the track is not an un-entered route. Phase 2 (#1220) judges only the
				// blocks the registry attributes to a train, so this holding is kept by both phases.
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
	fun `the block a train occupies never expires at any age`() {
		lateinit var blocks: List<DynamicTrackBlock>

		atSimTimes(
			0.0 to {
				blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
			},
			1.0 to {
				blocks.first().enter(FakeTrackOccupant("train1"))
				// The tick that first sees the train on the track starts its phase-2 stall clock.
				service.expireStaleReservations()
			},
			100_000.0 to { service.expireStaleReservations() },
			// Past the approach-lock booking window of the first call.
			100_002.0 to { service.expireStaleReservations() }
		)

		// The train stands on its first block: that block is never released, whatever its age ...
		assertThat(registry.getOwner(blocks.first())).isEqualTo("train1")
		assertThat(blocks.first().getState()).isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(expiry.expiredHoldingCount).isEqualTo(0)
		// ... while its stalled RESERVED tail goes, through phase 2 (#1220; pinned in detail by
		// ReservationExpiryOccupyingTrainTest).
		assertBlocksFreeAndUnowned(blocks.drop(1))
		assertThat(expiry.expiredTailCount).isEqualTo(1)
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
			(TTL - 1.0) to { results += service.reservePath("train2", inOut1, inOut2) },
			TTL to { results += service.reservePath("train2", inOut1, inOut2) }
		)

		assertThat(results[0]).isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		assertReservationSuccess(results[1])
		blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train2") }
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
	}

	@Test
	fun `a request refused by a Step 0 guard still expires another train's stale holding`() {
		lateinit var blocks: List<DynamicTrackBlock>
		lateinit var refused: PathReservationService.ReservationResult

		atSimTimes(
			0.0 to {
				blocks = assertReservationSuccess(service.reservePath("train2", inOut1, inOut2)).reservedBlocks
				entrySemaphoreOf(blocks).signal = Signal.STOP
			},
			// vA is a switch, i.e. a non-oriented end: refused before any candidate discovery.
			TTL to { refused = service.reservePath("train1", findSemaphoreByName("zA"), findSwitchByName("vA")) }
		)

		assertThat(refused).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		assertThat(service.getReservedBlocks("train2")).isEmpty()
		blocks.forEach { assertThat(registry.getOwner(it)).isNull() }
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
			(TTL - 1.0) to { results += service.reservePathToAny("train2", inOut1) },
			TTL to { results += service.reservePathToAny("train2", inOut1) }
		)

		assertThat(results[0]).isNotInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(results[1]).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(service.getReservedBlocks("train1")).isEmpty()
		assertThat(expiry.expiredHoldingCount).isEqualTo(1)
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
