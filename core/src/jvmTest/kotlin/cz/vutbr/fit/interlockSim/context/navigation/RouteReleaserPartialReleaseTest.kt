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
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.Test

/**
 * Switch protection of the partial release (`releaseAllBut`, Issue #1050) across its two
 * directions (PR #1194 review round):
 *
 * - a switch inside the KEPT head stays locked and owned, however the PathInfo trim cuts the
 *   freed tail off behind it;
 * - a switch inside the FREED tail is reclaimed (unlocked, unowned) by the per-block registry
 *   reclaim, however the head keeps running.
 *
 * `vyhybna.xml` cannot pin either direction: its trimmable cut sits next to an InOut, so no
 * keep head spans a switch, and every keep head that does span `vyhybna`'s switches ends at one
 * (the trim refuses, so the release defers wholesale instead of freeing anything). The
 * `two-switch-line.xml` fixture places one switch inside each direction; the straight line and
 * the uniform 24.0 m/s keep the expected lock states manually traceable. Both pins run through
 * the production entry (`service.releasePathDetailed`, the `NetworkActuatorPort` release).
 *
 * The same fixture pins the refused release (Issue #1242): a kept head that ends at `swX` cannot
 * be the end of a PathInfo, so the release must refuse BEFORE it touches any signal -- every aspect,
 * block, switch and the PathInfo stay as they were, and the refusal is logged at INFO once per
 * train, then at DEBUG, never at WARN.
 */
class RouteReleaserPartialReleaseTest : PathReservationServiceTestBase() {
	override fun loadTestContext(): DefaultSimulationContext =
		TestFixtures.loadTwoSwitchLineSimulationContext(simulationContextFactory, editingContextFactory)

	@Test
	fun `a partial release keeps the head's switch locked and reclaims the tail's one`() {
		val blocks = reserveWithSpannedHead()
		val headSwitch = findSwitchByName("swX")
		val tailSwitch = findSwitchByName("swY")

		val release = service.releasePathDetailed("train1")

		assertThat(headSwitch.locked, "the switch inside the kept head").isTrue()
		assertThat(registry.getSwitches("train1"), "switches owned after the partial release").contains(headSwitch)
		assertThat(tailSwitch.locked, "the switch inside the freed tail").isFalse()
		assertThat(release.released, "the freed tail").isEqualTo(blocks.drop(3))
		assertThat(release.deferred, "the kept head").isEqualTo(blocks.take(3))
		blocks.take(3).forEach { block ->
			assertThat(block.getState(), "state of kept $block").isEqualTo(TrackFacility.State.OCCUPIED)
			assertThat(registry.getOwner(block), "owner of kept $block").isEqualTo("train1")
		}
		blocks.drop(3).forEach { block ->
			assertThat(block.getState(), "state of freed $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of freed $block").isNull()
		}
	}

	@Test
	fun `a partial release survives one block's failed release event and frees the rest of the tail`() {
		val blocks = reserveWithSpannedHead()
		// The funnel emits one BlockReleased per freed block; the first listener throw aborts
		// only its own dropFreedBlock (caught per block, after that block's registry cleanup),
		// so the block is freed physically but dropped from the release's result, while the
		// release continues with the rest of the tail.
		environment.addBlockOccupancyListener(ThrowingOnceReleaseListener())

		val release = service.releasePathDetailed("train1")

		val failedBlock = blocks[3]
		assertThat(release.released, "the blocks freed after the throw").isEqualTo(blocks.drop(4))
		assertThat(failedBlock.getState(), "state of the block whose event threw")
			.isEqualTo(TrackFacility.State.FREE)
		assertThat(registry.getOwner(failedBlock), "the registry cleanup still ran").isNull()
	}

	@Test
	fun `a release refused because the PathInfo cannot be trimmed changes no signal and nothing else`() {
		withReleaserLog { appender ->
			val blocks = reserveWithUntrimmableHead()
			val aspectsBefore = semaphoreAspects()
			assertThat(aspectsBefore.values.filter { it.isAllowing() }, "proceed aspects to keep").isNotEmpty()
			val pathInfoBefore = registry.getPathInfo("train1")
			val switchesBefore = registry.getSwitches("train1")

			val first = service.releasePathDetailed("train1")
			val retry = service.releasePathDetailed("train1")

			listOf(first, retry).forEach { release ->
				assertThat(release.released, "freed by a refused release").isEmpty()
				assertThat(release.deferred, "reported as deferred").isEqualTo(blocks)
			}
			assertThat(semaphoreAspects(), "every aspect after the refused releases").isEqualTo(aspectsBefore)
			assertThat(service.hasClearedSignals("train1"), "cleared-signal bookkeeping kept").isTrue()
			assertThat(registry.getPathInfo("train1"), "the PathInfo").isSameInstanceAs(pathInfoBefore)
			assertThat(registry.getSwitches("train1"), "owned switches").isEqualTo(switchesBefore)
			assertThat(findSwitchByName("swX").locked, "swX").isTrue()
			assertThat(findSwitchByName("swY").locked, "swY").isTrue()
			assertThat(registry.getBlocks("train1")).containsExactly(*blocks.toTypedArray())
			blocks.take(2).forEach { assertThat(it.getState(), "state of $it").isEqualTo(TrackFacility.State.OCCUPIED) }
			blocks.drop(2).forEach { assertThat(it.getState(), "state of $it").isEqualTo(TrackFacility.State.RESERVED) }
			assertThat(appender.list.filter { it.level == Level.WARN }, "releaser WARN lines").isEmpty()
			assertThat(untrimmableLines(appender, Level.INFO, REFUSED), "INFO refusal lines").hasSize(1)
			assertThat(untrimmableLines(appender, Level.DEBUG, REFUSED), "DEBUG refusal lines").hasSize(1)
		}
	}

	@Test
	fun `an approach-locked head that blocks the cut still resets its signal and logs no WARN (the #1060 shape)`() {
		withReleaserLog { appender ->
			val blocks =
				assertReservationSuccess(service.reservePath("train1", inOutNamed("A"), inOutNamed("B")))
					.reservedBlocks
			blocks.first().enter(FakeTrackOccupant("train1"))
			val s1 = findSemaphoreByName("s1")
			// k2 is entered across s1 at proceed, so approach locking keeps it, and the cut after it
			// falls on the switch swX. The cut after k1 alone (at s1) is valid, so the reset is the
			// step that lets a call past k2's booking window free k2..k6: it is not refused.
			assertThat(s1.signal.isAllowing(), "s1 after the reservation").isTrue()

			val first = service.releasePathDetailed("train1")
			val retry = service.releasePathDetailed("train1")

			listOf(first, retry).forEach { release ->
				assertThat(release.released, "freed inside the booking window").isEmpty()
				assertThat(release.deferred, "reported as deferred").isEqualTo(blocks)
			}
			assertThat(s1.signal, "s1 after the deferred release").isEqualTo(Signal.STOP)
			assertThat(registry.getBlocks("train1")).containsExactly(*blocks.toTypedArray())
			assertThat(appender.list.filter { it.level == Level.WARN }, "releaser WARN lines").isEmpty()
			assertThat(untrimmableLines(appender, Level.INFO, DEFERRED), "INFO deferral lines").hasSize(1)
			assertThat(untrimmableLines(appender, Level.DEBUG, DEFERRED), "DEBUG deferral lines").hasSize(1)
		}
	}

	@Test
	fun `the refused-release line is INFO again once the train was unregistered`() {
		withReleaserLog { appender ->
			val occupant = FakeTrackOccupant("train1")
			val blocks = reserveWithUntrimmableHead(occupant)
			service.releasePathDetailed("train1")
			service.releasePathDetailed("train1")
			blocks.take(2).forEach { it.leave(occupant) }
			service.unregister("train1")
			assertThat(registry.getBlocks("train1"), "train1 left the registry").isEmpty()

			reserveWithUntrimmableHead()
			service.releasePathDetailed("train1")

			assertThat(untrimmableLines(appender, Level.INFO, REFUSED), "INFO refusal lines").hasSize(2)
			assertThat(untrimmableLines(appender, Level.DEBUG, REFUSED), "DEBUG refusal lines").hasSize(1)
		}
	}

	@Test
	fun `the refused-release line is INFO again once the train dropped its last block block by block`() {
		withReleaserLog { appender ->
			val occupant = FakeTrackOccupant("train1")
			val blocks = reserveWithUntrimmableHead(occupant)
			service.releasePathDetailed("train1")
			service.releasePathDetailed("train1")
			// The per-block funnel (dropFreedBlock), never the wholesale unregister: the train leaves
			// the registry when its last block goes.
			blocks.take(2).forEach { block ->
				block.leave(occupant)
				assertThat(service.unregisterBlock("train1", block), "unregisterBlock($block)").isTrue()
			}
			blocks.drop(2).forEach { block ->
				assertThat(service.releaseBlock("train1", block), "releaseBlock($block)").isTrue()
			}
			assertThat(registry.getBlocks("train1"), "train1 left the registry").isEmpty()

			// The per-block funnel leaves the PathInfo behind (it ends at B); drop it so the train can
			// come back on the same route. Only the PathInfo goes: the releaser state is untouched.
			registry.restorePathInfo("train1", null)
			reserveWithUntrimmableHead()
			service.releasePathDetailed("train1")

			assertThat(untrimmableLines(appender, Level.INFO, REFUSED), "INFO refusal lines").hasSize(2)
			assertThat(untrimmableLines(appender, Level.DEBUG, REFUSED), "DEBUG refusal lines").hasSize(1)
		}
	}

	@Test
	fun `the refused-release line is INFO again once a release of the train went ahead`() {
		withReleaserLog { appender ->
			val occupant = FakeTrackOccupant("train1")
			val blocks = reserveWithUntrimmableHead(occupant)
			service.releasePathDetailed("train1")
			// The head moves on to s3 (a valid PathInfo end) at STOP: the release goes ahead and frees
			// k4..k6.
			blocks[2].enter(occupant)
			findSemaphoreByName("s3").signal = Signal.STOP
			assertThat(service.releasePathDetailed("train1").released, "freed by the release").isEqualTo(blocks.drop(3))
			// An extension past s3 whose head ends at the switch swY is refused again.
			val extension =
				assertReservationSuccess(service.reservePath("train1", findSemaphoreByName("s3"), inOutNamed("B")))
					.reservedBlocks
			extension.first().enter(occupant)
			service.releasePathDetailed("train1")

			assertThat(untrimmableLines(appender, Level.INFO, REFUSED), "INFO refusal lines").hasSize(2)
			assertThat(untrimmableLines(appender, Level.DEBUG, REFUSED), "DEBUG refusal lines").isEmpty()
		}
	}

	@Test
	fun `a refusal after a deferral of the same train still logs its own INFO line`() {
		withReleaserLog { appender ->
			val occupant = FakeTrackOccupant("train1")
			val blocks =
				assertReservationSuccess(service.reservePath("train1", inOutNamed("A"), inOutNamed("B")))
					.reservedBlocks
			blocks.first().enter(occupant)
			// k2 is approach-locked behind s1 at proceed: deferred, the signal reset.
			service.releasePathDetailed("train1")
			// The head enters k2, which ends at swX: now even the occupied head cannot be cut after.
			blocks[1].enter(occupant)
			service.releasePathDetailed("train1")

			assertThat(untrimmableLines(appender, Level.INFO, DEFERRED), "INFO deferral lines").hasSize(1)
			assertThat(untrimmableLines(appender, Level.INFO, REFUSED), "INFO refusal lines").hasSize(1)
		}
	}

	/** The releaser's "cannot be trimmed" lines of one kind ([prefix]) about train1 at [level]. */
	private fun untrimmableLines(
		appender: ListAppender<ILoggingEvent>,
		level: Level,
		prefix: String
	): List<ILoggingEvent> =
		appender.list.filter {
			it.level == level &&
				it.formattedMessage.startsWith("$prefix'train1'") &&
				it.formattedMessage.contains("cannot be trimmed")
		}

	/**
	 * Reserves the route A→B and makes train1 span k1 and k2: the kept head ends at the switch swX,
	 * which is never a valid PathInfo end, so every detailed release is refused. k3 is entered
	 * across swX, so approach locking keeps nothing beyond the occupied head.
	 */
	private fun reserveWithUntrimmableHead(
		occupant: FakeTrackOccupant = FakeTrackOccupant("train1")
	): List<DynamicTrackBlock> {
		val blocks =
			assertReservationSuccess(service.reservePath("train1", inOutNamed("A"), inOutNamed("B")))
				.reservedBlocks
		assertThat(blocks, "the two-switch line's route").hasSize(6)
		blocks.take(2).forEach { it.enter(occupant) }
		return blocks
	}

	/**
	 * Reserves the route A→B on the two-switch line and makes train1 span its first three
	 * blocks, the shape both pins need: the kept head ends at the semaphore s3 (so it covers
	 * the switch swX from both sides) and the freed tail starts past s3, whose own switch swY
	 * goes stale. The pre-release s3 aspect is then returned to STOP: after the reservation it
	 * shows proceed, which would otherwise drag k4 in as approach-locked and leave nothing to
	 * free; with it at STOP the trim cuts at s3 (a semaphore, a valid PathInfo end) and frees
	 * k4..k6.
	 */
	private fun reserveWithSpannedHead(): List<DynamicTrackBlock> {
		val blocks =
			assertReservationSuccess(service.reservePath("train1", inOutNamed("A"), inOutNamed("B")))
				.reservedBlocks
		assertThat(blocks, "the two-switch line's route").hasSize(6)
		val occupant = FakeTrackOccupant("train1")
		blocks.take(3).forEach { it.enter(occupant) }
		findSemaphoreByName("s3").signal = Signal.STOP
		return blocks
	}

	/** A [BlockOccupancyListener] whose first BlockReleased notification throws, the later ones pass. */
	private class ThrowingOnceReleaseListener : BlockOccupancyListener {
		private var emitted = 0

		override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
			emitted++
			if (emitted == 1) throw IllegalStateException("release listener failure (test)")
		}
	}

	private companion object {
		/** The refusal line (Issue #1242) and the approach-lock deferral line, up to the train name. */
		const val REFUSED = "releasePath: refused for "
		const val DEFERRED = "releasePath: approach lock for "
	}
}
