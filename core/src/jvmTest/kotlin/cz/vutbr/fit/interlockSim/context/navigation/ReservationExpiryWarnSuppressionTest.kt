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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotInstanceOf
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * The WARN suppression of the kernel-side reservation expiry, Issue #1174 (SP12.8): a release that
 * throws is contained and logged at WARN once per failure episode, later failures of the same train
 * at DEBUG. Split out of [ReservationExpiryTest] in Issue #1229.
 *
 * Each scenario drives its own [DefaultReservationExpiry] whose release throws for one train and
 * hands every other train to the service's real [PathReservationService.releasePathDetailed]; the
 * expiry is built over the same registry and candidate screen wiring the service uses, so the
 * production class has no writable release hook.
 */
class ReservationExpiryWarnSuppressionTest : ReservationExpiryTestBase() {
	@Test
	fun `a throwing release is contained, logged once, and the other trains are still expired`() {
		withFailingRelease("train1") { appender, failing ->
			lateinit var train1Blocks: List<DynamicTrackBlock>
			val returned = mutableListOf<Int>()

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
				TTL to { returned += failing.expireStale(null) },
				PAST_TTL to { returned += failing.expireStale(null) },
				// Two calls more: still one WARN for train1.
				(TTL + 3.5) to { returned += failing.expireStale(null) }
			)

			// No crash, and train2 was expired although train1's release threw on every call ...
			assertThat(service.getReservedBlocks("train2")).isEmpty()
			assertThat(returned.sum()).isEqualTo(1)
			assertThat(failing.expiredHoldingCount).isEqualTo(1)
			// ... train1 keeps its holding for the next call ...
			train1Blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
			// ... and the failure was logged at WARN once, not on every call.
			assertThat(expiringWarnings(appender, "train1").size).isEqualTo(1)
		}
	}

	@Test
	fun `a failed self-request preserves the failed-expiry WARN suppression`() {
		withFailingRelease("train1") { appender, failing ->
			val zA = findSemaphoreByName("zA")
			val doB1 = findSemaphoreByName("doB1")
			val zB = findSemaphoreByName("zB")
			lateinit var blocks: List<DynamicTrackBlock>
			val results = mutableListOf<PathReservationService.ReservationResult>()

			atSimTimes(
				0.0 to {
					blocks = assertReservationSuccess(service.reservePath("train1", zA, doB1)).reservedBlocks
					// No proceed aspect at the head, so nothing is approach-locked.
					zA.signal = Signal.STOP
				},
				// First failure: logged at WARN, and the marker remembers train1.
				TTL to { failing.expireStale(null) },
				// The requester exemption drops train1 from this call's candidate set. The holding is
				// not attempted, so the marker must survive the call. The request itself is refused:
				// zB is not contiguous with the holding train1 holds.
				PAST_TTL to {
					// The call the request makes at its top, with the requester exempt ...
					failing.expireStale(exceptTrainId = "train1")
					// ... and the refused request itself.
					results += service.reservePath("train1", zB, inOutNamed("B"))
				},
				// The next loop tick releases train1 again; the failure must stay at DEBUG now.
				(TTL + 3.5) to { failing.expireStale(null) }
			)

			assertThat(results.single()).isNotInstanceOf<PathReservationService.ReservationResult.Success>()
			// The holding survived both calls: nothing was released, none of it is stale-released.
			blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
			assertThat(failing.expiredHoldingCount).isEqualTo(0)
			// The containment holds: exactly one WARN for train1's repeated release failure.
			assertThat(expiringWarnings(appender, "train1").size).isEqualTo(1)
		}
	}

	@Test
	fun `a train no longer stale is un-suppressed and warns fresh on a new episode`() {
		withFailingRelease("train1") { appender, failing ->
			lateinit var blocks: List<DynamicTrackBlock>
			lateinit var firstSegment: List<DynamicTrackBlock>

			atSimTimes(
				0.0 to {
					firstSegment =
						assertReservationSuccess(
							service.reservePath("train1", findSemaphoreByName("zA"), findSemaphoreByName("doB1"))
						).reservedBlocks
					findSemaphoreByName("zA").signal = Signal.STOP
				},
				// First episode: the failure is logged at WARN, and the marker remembers train1.
				TTL to { failing.expireStale(null) },
				// A successful extension claims new blocks, so the whole holding's newest claim is
				// fresh and the holding leaves the candidate set; the next loop tick (a call where
				// train1 is not the requester) clears the marker.
				PAST_TTL to {
					blocks = firstSegment +
						assertReservationSuccess(
							service.reservePath("train1", findSemaphoreByName("doB1"), inOutNamed("B"))
						).reservedBlocks
				},
				(TTL + 2.0) to { failing.expireStale(null) },
				// A TTL after the extension claim: the holding is stale again and fails again --
				// with a fresh WARN, because the marker was cleared.
				PAST_TWO_TTLS to { failing.expireStale(null) }
			)

			blocks.forEach { assertThat(registry.getOwner(it)).isEqualTo("train1") }
			assertThat(failing.expiredHoldingCount).isEqualTo(0)
			// One WARN per episode -- the new episode was not silenced by the old marker.
			assertThat(expiringWarnings(appender, "train1").size).isEqualTo(2)
		}
	}

	/**
	 * Runs [body] with a Logback appender attached to the expiry class and an expiry built over this
	 * test's registry whose release throws for [trainId] and is the service's real release for every
	 * other train; the appender is detached in `finally`.
	 */
	private fun withFailingRelease(
		trainId: String,
		body: (ListAppender<ILoggingEvent>, DefaultReservationExpiry) -> Unit
	) {
		val expiryLogger = LoggerFactory.getLogger(DefaultReservationExpiry::class.java.name) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		expiryLogger.addAppender(appender)
		val failing =
			DefaultReservationExpiry(
				registry = registry,
				candidateScreen =
					RouteCandidateScreen(
						registry,
						environment,
						ReservationTargetDiscovery(navigator, environment),
						pathInfoBuilder
					),
				ttlSimSeconds = TTL,
				expiryRelease = { holding ->
					check(holding != trainId) { "release of $trainId fails under test" }
					service.releasePathDetailed(holding)
				}
			)
		try {
			body(appender, failing)
		} finally {
			expiryLogger.detachAppender(appender)
		}
	}

	/** This run's failures of [trainId]'s expiry release, logged at WARN. */
	private fun expiringWarnings(
		appender: ListAppender<ILoggingEvent>,
		trainId: String
	): List<ILoggingEvent> =
		appender.list.filter {
			it.level == Level.WARN && it.formattedMessage.startsWith("Reservation expiry of '$trainId' failed")
		}
}
