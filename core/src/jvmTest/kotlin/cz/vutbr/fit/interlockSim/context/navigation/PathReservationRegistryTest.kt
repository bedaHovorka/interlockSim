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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.RecordingBlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import cz.vutbr.fit.interlockSim.testutil.throwingSwitchUnlockListener
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.test.inject

/**
 * Comprehensive test suite for PathReservationRegistry atomic operations.
 *
 * @since Issue #292 Phase 2 Enhancement (Plan Phase 2)
 */
@Tag("integration-test")
class PathReservationRegistryTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var registry: PathReservationRegistry
	private lateinit var blocks: List<DynamicTrackBlock>
	private lateinit var simulationContext: DefaultSimulationContext

	@BeforeEach
	fun setUp() {
		// Load vyhybna.xml to get real DynamicTrackBlock instances
		simulationContext =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()

		// Get registry from context's scope (Issue #296 Phase 8: now requires context)
		registry = simulationContext.scope.get()

		// Get all blocks from the graph using a navigator
		val inOuts = simulationContext.getInOuts().toList()
		val navigator: TopologyNavigator = simulationContext.scope.get()
		val paths = navigator.findAllTopologicalPaths(inOuts[0], inOuts[1])

		blocks =
			paths
				.first()
				.mapNotNull { section ->
					val block = section.getTrackBlock()
					if (block is DynamicTrackBlock) block else null
				}.distinct()
	}

	@Nested
	inner class RegisterAtomicSuccess {
		@Test
		fun `registerAtomic succeeds when no conflicts exist`() {
			// Act
			val result = registry.registerAtomic("train1", blocks)

			// Assert
			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()

			// Verify bidirectional mapping
			val registeredBlocks = registry.getBlocks("train1")
			assertThat(registeredBlocks).containsExactly(*blocks.toTypedArray())

			blocks.forEach { block ->
				assertThat(registry.getOwner(block)).isEqualTo("train1")
				assertThat(registry.isRegistered(block)).isEqualTo(true)
			}
		}

		@Test
		fun `registerAtomic adds to existing train registration`() {
			// Arrange - register first 3 blocks
			val firstBatch = blocks.take(3)
			val secondBatch = blocks.drop(3)

			registry.registerAtomic("train1", firstBatch)

			// Act - register remaining blocks for same train
			val result = registry.registerAtomic("train1", secondBatch)

			// Assert
			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()

			val allRegistered = registry.getBlocks("train1")
			assertThat(allRegistered.size).isEqualTo(blocks.size)
		}

		@Test
		fun `registerAtomic is idempotent for same train`() {
			// Arrange - register blocks for train1
			registry.registerAtomic("train1", blocks)

			// Act - register same blocks again for same train
			val result = registry.registerAtomic("train1", blocks)

			// Assert - should succeed (idempotent)
			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()

			// Verify no duplicates
			val registeredBlocks = registry.getBlocks("train1")
			assertThat(registeredBlocks.size).isEqualTo(blocks.size)
		}
	}

	@Nested
	inner class RegisterAtomicConflict {
		@Test
		fun `registerAtomic returns Conflict when block already owned by different train`() {
			// Arrange - train1 registers all blocks
			registry.registerAtomic("train1", blocks)

			// Act - train2 attempts to register same blocks
			val result = registry.registerAtomic("train2", blocks)

			// Assert
			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Conflict>()

			val conflict = result as PathReservationRegistry.RegistrationResult.Conflict
			assertThat(conflict.conflictingBlock).isEqualTo(blocks.first())
			assertThat(conflict.existingOwner).isEqualTo("train1")
			assertThat(conflict.reason)
				.isEqualTo(PathReservationRegistry.ConflictReason.RESERVED_BY_OTHER_TRAIN)
		}

		@Test
		fun `registerAtomic detects conflict on first conflicting block`() {
			// Arrange - train1 registers second block only
			val secondBlock = blocks[1]
			registry.registerAtomic("train1", listOf(secondBlock))

			// Act - train2 attempts to register all blocks (including second)
			val result = registry.registerAtomic("train2", blocks)

			// Assert - should fail on second block
			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Conflict>()

			val conflict = result as PathReservationRegistry.RegistrationResult.Conflict
			assertThat(conflict.conflictingBlock).isEqualTo(secondBlock)
			assertThat(conflict.existingOwner).isEqualTo("train1")
			assertThat(conflict.reason)
				.isEqualTo(PathReservationRegistry.ConflictReason.RESERVED_BY_OTHER_TRAIN)
		}
	}

	@Nested
	inner class Atomicity {
		@Test
		fun `registerAtomic registers nothing when conflict detected (atomicity)`() {
			// Arrange - train1 registers last block
			val lastBlock = blocks.last()
			registry.registerAtomic("train1", listOf(lastBlock))

			// Act - train2 attempts to register all blocks
			val result = registry.registerAtomic("train2", blocks)

			// Assert - registration failed
			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Conflict>()

			// Verify NO blocks are owned by train2 (atomicity guarantee)
			val train2Blocks = registry.getBlocks("train2")
			assertThat(train2Blocks).isEmpty()

			// Verify first blocks are NOT owned by train2
			blocks.dropLast(1).forEach { block ->
				val owner = registry.getOwner(block)
				assertThat(owner == null || owner != "train2").isTrue()
			}

			// Verify train1 still owns last block
			assertThat(registry.getOwner(lastBlock)).isEqualTo("train1")

			// Verify reason is "reserved" (block is reserved, not physically occupied)
			val conflict = result as PathReservationRegistry.RegistrationResult.Conflict
			assertThat(conflict.reason)
				.isEqualTo(PathReservationRegistry.ConflictReason.RESERVED_BY_OTHER_TRAIN)
		}
	}

	@Nested
	inner class BidirectionalMapping {
		@Test
		fun `getOwner returns null for unregistered block`() {
			// Act
			val owner = registry.getOwner(blocks.first())

			// Assert
			assertThat(owner).isEqualTo(null)
		}

		@Test
		fun `getBlocks returns empty list for unknown train`() {
			// Act
			val blocks = registry.getBlocks("unknown-train")

			// Assert
			assertThat(blocks).isEmpty()
		}

		@Test
		fun `isRegistered returns false for unregistered block`() {
			// Act
			val registered = registry.isRegistered(blocks.first())

			// Assert
			assertThat(registered).isEqualTo(false)
		}
	}

	@Nested
	inner class Unregister {
		@Test
		fun `unregister removes all blocks and bidirectional mappings`() {
			// Arrange
			registry.registerAtomic("train1", blocks)

			// Act
			val released = registry.unregister("train1")

			// Assert
			assertThat(released).containsExactly(*blocks.toTypedArray())

			// Verify train mapping removed
			val remainingBlocks = registry.getBlocks("train1")
			assertThat(remainingBlocks).isEmpty()

			// Verify block mappings removed
			blocks.forEach { block ->
				assertThat(registry.getOwner(block)).isEqualTo(null)
				assertThat(registry.isRegistered(block)).isEqualTo(false)
			}
		}

		@Test
		fun `unregister returns empty list for unknown train`() {
			// Act
			val released = registry.unregister("unknown-train")

			// Assert
			assertThat(released).isEmpty()
		}
	}

	/**
	 * Issue #975 (owner decision D5): the registry records when each block was registered, as a
	 * read-only fact. No expiry lives in the registry; staleness stays the sweeper's own rule.
	 */
	@Nested
	inner class RegistrationTimestamps {
		private var now = 0.0
		private lateinit var clocked: PathReservationRegistry

		@BeforeEach
		fun setUpClock() {
			now = 0.0
			clocked = PathReservationRegistry(simulationContext) { now }
		}

		@Test
		fun `registering a block records the sim time it was registered at`() {
			now = 12.5

			clocked.registerAtomic("train1", blocks)

			blocks.forEach { block ->
				assertThat(clocked.getRegisteredAtSimTime(block), "timestamp of $block").isEqualTo(12.5)
			}
		}

		@Test
		fun `a block never registered has no timestamp`() {
			assertThat(clocked.getRegisteredAtSimTime(blocks.first())).isNull()
		}

		@Test
		fun `re-registering a block the train already holds keeps its first timestamp`() {
			now = 10.0
			clocked.registerAtomic("train1", blocks.take(2))

			now = 40.0
			clocked.registerAtomic("train1", blocks.take(3))

			assertThat(clocked.getRegisteredAtSimTime(blocks[0]), "already held").isEqualTo(10.0)
			assertThat(clocked.getRegisteredAtSimTime(blocks[1]), "already held").isEqualTo(10.0)
			assertThat(clocked.getRegisteredAtSimTime(blocks[2]), "newly registered").isEqualTo(40.0)
		}

		@Test
		fun `a conflicting registration records no timestamp`() {
			clocked.registerAtomic("train1", blocks.take(1))

			val result = clocked.registerAtomic("train2", blocks)

			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Conflict>()
			blocks.drop(1).forEach { block ->
				assertThat(clocked.getRegisteredAtSimTime(block), "timestamp of $block").isNull()
			}
		}

		@Test
		fun `unregisterBlock removes the block's timestamp`() {
			clocked.registerAtomic("train1", blocks)

			assertThat(clocked.unregisterBlock("train1", blocks.first())).isTrue()

			assertThat(clocked.getRegisteredAtSimTime(blocks.first()), "released block").isNull()
			assertThat(clocked.getRegisteredAtSimTime(blocks.last()), "still held block").isNotNull()
		}

		@Test
		fun `a refused unregisterBlock keeps the timestamp`() {
			clocked.registerAtomic("train1", blocks)

			assertThat(clocked.unregisterBlock("train2", blocks.first())).isFalse()

			assertThat(clocked.getRegisteredAtSimTime(blocks.first())).isEqualTo(0.0)
		}

		@Test
		fun `an unregisterBlock refused because the block is still reserved keeps the timestamp`() {
			// PR #1115 review: the realistic refusal is by the owner itself, for a block not yet FREE.
			now = 7.0
			val block = blocks.first()
			clocked.registerAtomic("train1", listOf(block))
			block.setUpPath(block.ends().first() as DynamicPathSeparator, "train1")

			assertThat(clocked.unregisterBlock("train1", block)).isFalse()

			assertThat(clocked.getRegisteredAtSimTime(block)).isEqualTo(7.0)
			assertThat(clocked.getOwner(block)).isEqualTo("train1")
		}

		@Test
		fun `unregister of a train removes the timestamps of all its blocks`() {
			clocked.registerAtomic("train1", blocks)

			clocked.unregister("train1")

			blocks.forEach { block ->
				assertThat(clocked.getRegisteredAtSimTime(block), "timestamp of $block").isNull()
			}
		}

		@Test
		fun `clear removes every timestamp`() {
			clocked.registerAtomic("train1", blocks.take(2))
			clocked.registerAtomic("train2", blocks.drop(2))

			clocked.clear()

			blocks.forEach { block ->
				assertThat(clocked.getRegisteredAtSimTime(block), "timestamp of $block").isNull()
			}
		}

		@Test
		fun `a block registered again after its release gets a new timestamp`() {
			now = 5.0
			clocked.registerAtomic("train1", blocks.take(1))
			clocked.unregisterBlock("train1", blocks.first())

			now = 90.0
			clocked.registerAtomic("train2", blocks.take(1))

			assertThat(clocked.getRegisteredAtSimTime(blocks.first())).isEqualTo(90.0)
		}

		@Test
		fun `the default time source works without a running simulation`() {
			// The context's own registry uses the default source; no kDisco process is running here.
			registry.registerAtomic("train1", blocks)

			assertThat(registry.getRegisteredAtSimTime(blocks.first())).isEqualTo(0.0)
		}
	}

	@Nested
	inner class Statistics {
		@Test
		fun `trainCount returns number of trains with reservations`() {
			// Arrange
			val blocks1 = blocks.take(2)
			val blocks2 = blocks.drop(2).take(2)

			registry.registerAtomic("train1", blocks1)
			registry.registerAtomic("train2", blocks2)

			// Act
			val count = registry.trainCount()

			// Assert
			assertThat(count).isEqualTo(2)
		}

		@Test
		fun `blockCount returns total number of registered blocks`() {
			// Arrange
			val blocks1 = blocks.take(3)
			val blocks2 = blocks.drop(3).take(2)

			registry.registerAtomic("train1", blocks1)
			registry.registerAtomic("train2", blocks2)

			// Act
			val count = registry.blockCount()

			// Assert
			assertThat(count).isEqualTo(5)
		}

		@Test
		fun `clear removes all registrations`() {
			// Arrange
			registry.registerAtomic("train1", blocks.take(3))
			registry.registerAtomic("train2", blocks.drop(3))

			// Act
			registry.clear()

			// Assert
			assertThat(registry.trainCount()).isEqualTo(0)
			assertThat(registry.blockCount()).isEqualTo(0)
		}
	}

	@Nested
	inner class SwitchOwnership {
		private fun switches(): List<DynamicRailSwitch> = simulationContext.cellsOfType<DynamicRailSwitch>()

		@Test
		fun `registerSwitches throws when a switch is already registered to a different train`() {
			// Arrange - Issue #1076: a plain overwrite used to silently steal the entry.
			val switch = switches().first()
			registry.registerSwitches("train1", listOf(switch))

			// Act + Assert - the foreign registration is rejected...
			assertFailure { registry.registerSwitches("train2", listOf(switch)) }
				.isInstanceOf(IllegalStateException::class)

			// ...and BOTH maps still agree on the original owner, with the lock intact.
			assertThat(registry.getSwitchOwner(switch)).isEqualTo("train1")
			assertThat(registry.getSwitches("train1")).containsExactly(switch)
			assertThat(registry.getSwitches("train2")).isEmpty()
			assertThat(switch.locked).isTrue()
		}

		@ParameterizedTest(name = "flank = {0}")
		@ValueSource(booleans = [false, true])
		fun `a mixed list is rejected atomically without partial registration`(flank: Boolean) {
			// Arrange - train1 owns only the SECOND switch of train2's list.
			val (free, owned) = switches().take(2)
			registry.registerSwitches("train1", listOf(owned))

			// Act + Assert - the whole registration fails, for plain and flank registration alike...
			assertFailure {
				if (flank) {
					registry.registerFlankSwitches("train2", listOf(free, owned))
				} else {
					registry.registerSwitches("train2", listOf(free, owned))
				}
			}.isInstanceOf(IllegalStateException::class)

			// ...and the free switch was NOT registered, locked, or marked (pre-check before mutation).
			assertThat(registry.getSwitchOwner(free)).isNull()
			assertThat(free.locked).isFalse()
			assertThat(registry.isFlankProtected(free)).isFalse()
			assertThat(registry.getSwitches("train2")).isEmpty()
			assertThat(registry.getSwitchOwner(owned)).isEqualTo("train1")
		}

		@Test
		fun `registerSwitches re-registration by the same train stays a no-op`() {
			val switch = switches().first()
			registry.registerSwitches("train1", listOf(switch))

			// Re-registering the train's own switch (route extension) must not throw.
			registry.registerSwitches("train1", listOf(switch))

			assertThat(registry.getSwitchOwner(switch)).isEqualTo("train1")
			assertThat(registry.getSwitches("train1")).containsExactly(switch)
			assertThat(switch.locked).isTrue()
		}

		@Test
		fun `flank protection marker follows the registration lifecycle`() {
			val switch = switches().first()
			registry.registerFlankSwitches("train1", listOf(switch))

			// Registered as flank: owned, locked, and marked for reclamation to skip it.
			assertThat(registry.getSwitchOwner(switch)).isEqualTo("train1")
			assertThat(switch.locked).isTrue()
			assertThat(registry.isFlankProtected(switch)).isTrue()

			// Release clears the marker together with the ownership...
			assertThat(registry.unregisterSwitch("train1", switch)).isTrue()
			assertThat(registry.isFlankProtected(switch)).isFalse()
			assertThat(registry.getSwitchOwner(switch)).isNull()

			// ...and a later NORMAL registration does not restore it (the marker is
			// purpose-specific, not a property of the switch).
			registry.registerSwitches("train1", listOf(switch))
			assertThat(registry.isFlankProtected(switch)).isFalse()
		}

		@Test
		fun `unregisterSwitches clears the flank protection marker`() {
			val (flankSwitch, plainSwitch) = switches().take(2)
			registry.registerFlankSwitches("train1", listOf(flankSwitch))
			registry.registerSwitches("train1", listOf(plainSwitch))

			registry.unregisterSwitches("train1")

			assertThat(registry.isFlankProtected(flankSwitch)).isFalse()
			assertThat(registry.getSwitchOwner(flankSwitch)).isNull()
			assertThat(registry.getSwitches("train1")).isEmpty()
		}

		@Test
		fun `a switch whose owner holds no block at all is stale ownership`() {
			// PR #1115 review: the owner has no block entry at all, not just none next to the switch.
			val (plainSwitch, flankSwitch) = switches().take(2)
			assertThat(registry.isStaleSwitchOwnership(plainSwitch), "no claim").isFalse()
			registry.registerSwitches("train1", listOf(plainSwitch))
			registry.registerFlankSwitches("train1", listOf(flankSwitch))

			assertThat(registry.getBlocks("train1"), "blocks of train1").isEmpty()
			assertThat(registry.isStaleSwitchOwnership(plainSwitch), "plain claim").isTrue()
			assertThat(registry.isStaleSwitchOwnership(flankSwitch), "flank claim").isFalse()
		}

		@Test
		fun `a claim records the owner and the purpose in one record`() {
			// Issue #1103: one record per switch, so the purpose cannot outlive the ownership.
			val (flankSwitch, plainSwitch) = switches().take(2)
			registry.registerFlankSwitches("train1", listOf(flankSwitch))
			registry.registerSwitches("train1", listOf(plainSwitch))

			assertThat(registry.getSwitchClaim(flankSwitch)).isEqualTo(SwitchClaim("train1", isFlank = true))
			assertThat(registry.getSwitchClaim(plainSwitch)).isEqualTo(SwitchClaim("train1", isFlank = false))
		}

		@Test
		fun `every removal drops the purpose with the claim`() {
			val (first, second) = switches().take(2)

			fun assertNoClaim(
				switch: DynamicRailSwitch,
				after: String
			) {
				assertThat(registry.getSwitchClaim(switch), "claim after $after").isNull()
				assertThat(registry.isFlankProtected(switch), "flank after $after").isFalse()
			}
			registry.registerFlankSwitches("train1", listOf(first))
			registry.registerFlankSwitches("train2", listOf(second))

			assertThat(registry.unregisterSwitch("train1", first)).isTrue()
			assertNoClaim(first, "unregisterSwitch")
			registry.unregisterSwitches("train2")
			assertNoClaim(second, "unregisterSwitches")

			registry.registerFlankSwitches("train3", listOf(first))
			registry.clear()
			assertNoClaim(first, "clear")
		}

		@Test
		fun `a plain re-registration by the same train keeps the flank purpose`() {
			// The registry cannot tell whether the earlier flank route is still live, so it keeps the
			// conservative answer until the claim is released.
			val switch = switches().first()
			registry.registerFlankSwitches("train1", listOf(switch))

			registry.registerSwitches("train1", listOf(switch))

			assertThat(registry.getSwitchClaim(switch)).isEqualTo(SwitchClaim("train1", isFlank = true))
			assertThat(registry.getSwitches("train1")).containsExactly(switch)
		}

		@Test
		fun `registerFlankSwitches on an empty list registers nothing`() {
			registry.registerFlankSwitches("train1", emptyList())

			assertThat(registry.getSwitches("train1")).isEmpty()
		}

		/**
		 * A switch on the reserved path together with the two held blocks it joins, in path order:
		 * a train straddling the switch holds both, a train that has passed it holds only the second.
		 */
		private fun straddledSwitch(): Triple<DynamicTrackBlock, DynamicRailSwitch, DynamicTrackBlock> {
			val (before, after) =
				blocks.zipWithNext().first { (before, after) ->
					before.ends().any { it is DynamicRailSwitch && it in after.ends() }
				}
			val switch = before.ends().filterIsInstance<DynamicRailSwitch>().first { it in after.ends() }
			return Triple(before, switch, after)
		}

		@ParameterizedTest(name = "release-time reclaim, flank = {0}")
		@ValueSource(booleans = [false, true])
		fun `unregisterBlock reclaims a stale plain claim at the freed block's ends and keeps a flank claim`(flank: Boolean) {
			// Arrange - Issue #1103: train1 holds ONE block bounded by the switch and claims the switch.
			val (_, switch, block) = straddledSwitch()
			assertThat(registry.registerAtomic("train1", listOf(block)))
				.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
			if (flank) {
				registry.registerFlankSwitches("train1", listOf(switch))
			} else {
				registry.registerSwitches("train1", listOf(switch))
			}

			// Act - the block leaves the registry; train1 now holds no block bounded by the switch.
			assertThat(registry.unregisterBlock("train1", block)).isTrue()

			// Assert - a plain claim is stale and goes with the block; a flank claim protects a route the
			// switch is not adjacent to and survives (Issue #1076 review).
			if (flank) {
				assertThat(registry.getSwitchOwner(switch), "flank owner").isEqualTo("train1")
				assertThat(registry.isFlankProtected(switch)).isTrue()
				assertThat(switch.locked, "flank lock").isTrue()
			} else {
				assertThat(registry.getSwitchOwner(switch), "plain owner").isNull()
				assertThat(registry.getSwitches("train1")).isEmpty()
				assertThat(switch.locked, "plain lock").isFalse()
			}
		}

		@Test
		fun `unregisterBlock keeps a plain claim whose owner still holds the other block at the switch`() {
			// Arrange - train1 straddles the switch: it holds the block before and the block after it.
			val (before, switch, after) = straddledSwitch()
			assertThat(registry.registerAtomic("train1", listOf(before, after)))
				.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
			registry.registerSwitches("train1", listOf(switch))

			// Act - the block behind the train is freed; the one in front is still held.
			assertThat(registry.unregisterBlock("train1", before)).isTrue()

			// Assert - the switch still protects a live route and must not move under the train.
			assertThat(switch.locked, "lock").isTrue()
			assertThat(registry.getSwitchOwner(switch), "owner").isEqualTo("train1")
			assertThat(registry.getSwitches("train1")).containsExactly(switch)
			assertThat(registry.isStaleSwitchOwnership(switch)).isFalse()
		}

		@Test
		fun `sectional release frees the switch only with the last held block bounded by it`() {
			// Arrange - train1 holds before | switch | after and claims the switch.
			val (before, switch, after) = straddledSwitch()
			assertThat(registry.registerAtomic("train1", listOf(before, after)))
				.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
			registry.registerSwitches("train1", listOf(switch))
			val positionHeld = switch.conf

			// Act 1 - the first block is freed: the claim stays, and a second train is still refused.
			assertThat(registry.unregisterBlock("train1", before)).isTrue()
			assertThat(registry.getSwitchOwner(switch), "owner after the first release").isEqualTo("train1")
			assertFailure { registry.registerSwitches("train2", listOf(switch)) }
				.isInstanceOf(IllegalStateException::class)

			// Act 2 - the last held block bounded by the switch is freed: the claim is released with it.
			assertThat(registry.unregisterBlock("train1", after)).isTrue()

			// Assert - the switch is free for the second train's route in the OPPOSITE position.
			assertThat(registry.getSwitchOwner(switch), "owner after the second release").isNull()
			assertThat(switch.locked, "lock after the second release").isFalse()
			switch.changeConf()
			assertThat(switch.conf).isNotEqualTo(positionHeld)
			registry.registerSwitches("train2", listOf(switch))
			assertThat(registry.getSwitchOwner(switch), "second train's claim").isEqualTo("train2")
			assertThat(switch.locked, "second train's lock").isTrue()
		}

		@Test
		fun `a throwing switch listener does not abort the release nor the reclaim`() {
			// Arrange - Issue #1103 review: DynamicRailSwitch fires property listeners synchronously
			// from inside the reclaim's unregisterSwitch. The release funnel must survive that
			// throw, and the switch's per-listener containment (the review's ruling C) must leave
			// the reclaim able to clean the claim in the same call.
			val (before, switch, after) = straddledSwitch()
			assertThat(registry.registerAtomic("train1", listOf(before, after)))
				.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
			registry.registerSwitches("train1", listOf(switch))
			val throwing = throwingSwitchUnlockListener()
			switch.addPropertyChangeListener(throwing)

			// Act 1 - free the block BEFORE the switch: the claim is not stale yet (the train still
			// holds the block after the switch), so the reclaim keeps it.
			assertThat(registry.unregisterBlock("train1", before)).isTrue()
			assertThat(registry.getSwitchOwner(switch), "owner after act 1").isEqualTo("train1")

			// Act 2 - the LAST block bounded by the switch is freed, so the reclaim runs
			// unregisterSwitch -> unlock() -> the throwing listener throws into the notify loop.
			assertThat(registry.unregisterBlock("train1", after)).isTrue()

			// Assert - the release completed and the reclaim fully cleaned the claim: the cell-level
			// containment logged the failure and ran on, so the map cleanup after unlock() also ran.
			// The block mappings are gone, the switch is unlocked and immediately claimable again.
			assertThat(registry.getBlocks("train1")).isEmpty()
			assertThat(registry.getSwitchOwner(switch), "owner after the reclaim").isNull()
			assertThat(registry.getSwitches("train1")).isEmpty()
			assertThat(switch.locked, "lock after the reclaim").isFalse()
			registry.registerSwitches("train2", listOf(switch))
			assertThat(registry.getSwitchOwner(switch), "second train's claim").isEqualTo("train2")
		}
	}

	@Nested
	inner class OccupantTracking {
		@Test
		fun `getOccupant returns null for unoccupied block`() {
			registry.registerAtomic("train1", blocks.take(1))

			assertThat(registry.getOccupant(blocks.first())).isEqualTo(null)
			assertThat(registry.getOccupantName(blocks.first())).isEqualTo(null)
			assertThat(registry.isOccupied(blocks.first())).isEqualTo(false)
		}

		@Test
		fun `getOccupant returns occupant after train enters block`() {
			val block = blocks.first()
			val separator = block.ends().first() as cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
			block.setUpPath(separator, "train1")

			val occupant = FakeTrackOccupant("train1")
			block.enter(occupant)

			assertThat(registry.getOccupant(block)).isSameInstanceAs(occupant)
			assertThat(registry.getOccupantName(block)).isEqualTo("train1")
			assertThat(registry.isOccupied(block)).isEqualTo(true)
		}

		@Test
		fun `getOccupiedBlocks returns only blocks physically occupied by train`() {
			val firstBlock = blocks[0]
			val secondBlock = blocks[1]
			val separator1 = firstBlock.ends().first() as cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
			val separator2 = secondBlock.ends().first() as cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

			registry.registerAtomic("train1", listOf(firstBlock, secondBlock))
			firstBlock.setUpPath(separator1, "train1")
			secondBlock.setUpPath(separator2, "train1")
			firstBlock.enter(FakeTrackOccupant("train1"))

			assertThat(registry.getOccupiedBlocks("train1"))
				.containsExactly(firstBlock)
		}
	}

	@Nested
	inner class OccupiedConflictDetection {
		@Test
		fun `registerAtomic reports occupied conflict when block is physically occupied`() {
			val block = blocks.first()
			val separator = block.ends().first() as cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

			// Train1 reserves and physically enters the block
			block.setUpPath(separator, "train1")
			block.enter(FakeTrackOccupant("train1"))
			registry.registerAtomic("train1", listOf(block))

			// Train2 attempts to reserve the same block
			val result = registry.registerAtomic("train2", listOf(block))

			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Conflict>()
			val conflict = result as PathReservationRegistry.RegistrationResult.Conflict
			assertThat(conflict.existingOwner).isEqualTo("train1")
			assertThat(conflict.reason)
				.isEqualTo(PathReservationRegistry.ConflictReason.OCCUPIED_BY_OTHER_TRAIN)
		}
	}

	@Nested
	inner class DefenceInDepth {
		@Test
		fun `registerAtomic detects conflict when block state diverges from registry`() {
			val block = blocks.first()
			val separator = block.ends().first() as cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

			// Block is reserved directly without registry record
			block.setUpPath(separator, "train1")

			val result = registry.registerAtomic("train2", listOf(block))

			assertThat(result).isInstanceOf<PathReservationRegistry.RegistrationResult.Conflict>()
			val conflict = result as PathReservationRegistry.RegistrationResult.Conflict
			assertThat(conflict.existingOwner).isEqualTo("train1")
			assertThat(conflict.reason)
				.isEqualTo(PathReservationRegistry.ConflictReason.RESERVED_BY_OTHER_TRAIN)
		}
	}

	@Nested
	inner class SameStepConcurrency {
		@Test
		fun `two reservation attempts for the same block cannot both succeed`() {
			val block = blocks.first()

			val first = registry.registerAtomic("train1", listOf(block))
			val second = registry.registerAtomic("train2", listOf(block))

			assertThat(first).isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
			assertThat(second).isInstanceOf<PathReservationRegistry.RegistrationResult.Conflict>()
			assertThat(registry.getOwner(block)).isEqualTo("train1")
		}
	}

	@Nested
	inner class BlockOccupancyListeners {
		private fun fakeEvent(block: DynamicTrackBlock): BlockOccupancyEvent =
			BlockOccupancyEvent(
				block = block,
				type = BlockOccupancyEventType.BLOCK_RELEASED,
				trainId = "train-test",
				occupant = null,
				previousState = TrackFacility.State.OCCUPIED,
				newState = TrackFacility.State.FREE,
				simulationTime = 42.0
			)

		@Test
		fun `addBlockOccupancyListener delivers events to subscriber`() {
			val listener = RecordingBlockOccupancyListener()
			val block = blocks.first()
			val event = fakeEvent(block)

			registry.addBlockOccupancyListener(listener)
			registry.emit(event)

			assertThat(listener.events).containsExactly(event)
		}

		@Test
		fun `removeBlockOccupancyListener stops event delivery`() {
			val listener = RecordingBlockOccupancyListener()
			val block = blocks.first()
			val event = fakeEvent(block)

			registry.addBlockOccupancyListener(listener)
			registry.removeBlockOccupancyListener(listener)
			registry.emit(event)

			assertThat(listener.events).isEmpty()
		}

		@Test
		fun `multiple listeners receive the same event in registration order`() {
			val first = RecordingBlockOccupancyListener()
			val second = RecordingBlockOccupancyListener()
			val block = blocks.first()
			val event = fakeEvent(block)

			registry.addBlockOccupancyListener(first)
			registry.addBlockOccupancyListener(second)
			registry.emit(event)

			assertThat(first.events).containsExactly(event)
			assertThat(second.events).containsExactly(event)
			assertThat(first.events[0]).isSameInstanceAs(second.events[0])
		}
	}

	/**
	 * Issue #1063: a partial tail release must be able to cut the stored PathInfo back to the
	 * separator where the occupied head meets the released tail. Otherwise `isPathExtendedBeyond`
	 * keeps reporting a route the train no longer holds and both dispatchers stay silent.
	 */
	@Nested
	inner class TrimPathInfoTo {
		private val trainId = "trimTrain"

		@Test
		fun `trims the PathInfo to the boundary once every block beyond it is released`() {
			val held = reserveLongRoute(trainId)
			val head = held.first { block -> zA() in block.ends() && block.ends().none { it is DynamicRailSwitch } }
			// Release the tail the way RegistryPartialRouteReleaser does: free each block first, because
			// unregisterBlock only accepts a FREE block.
			held.filter { it != head }.forEach { block ->
				block.cancelPathSetup(requireNotNull(block.reservedFrom) { "a reserved block has a reservedFrom" })
				assertThat(registry.unregisterBlock(trainId, block)).isTrue()
			}

			val trimmed = registry.trimPathInfoTo(trainId, zA())

			assertThat(trimmed).isTrue()
			val pathInfo = registry.getPathInfo(trainId)!!
			assertThat(pathInfo.target).isEqualTo(zA())
			assertThat(pathInfo.reservedPath.last()).isEqualTo(zA())
			assertThat(pathInfo.entryDirections.keys.toList()).containsExactly(head)
			assertThat(registry.isPathExtendedBeyond(trainId, zA())).isFalse()
		}

		@Test
		fun `refuses to trim while a held block still lies beyond the boundary`() {
			reserveLongRoute(trainId)
			val before = registry.getPathInfo(trainId)

			val trimmed = registry.trimPathInfoTo(trainId, zA())

			assertThat(trimmed).isFalse()
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(before)
		}

		@Test
		fun `refuses to trim to a separator the stored path does not pass`() {
			reserveLongRoute(trainId)
			val before = registry.getPathInfo(trainId)!!
			val doA1 = separatorAt(16, 8)
			val notOnPath = if (before.reservedPath.contains(doA1)) separatorAt(17, 9) else doA1

			val trimmed = registry.trimPathInfoTo(trainId, notOnPath)

			assertThat(trimmed).isFalse()
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(before)
		}

		/**
		 * A route runs signal to signal; a switch is interior, never an end (Issue #938). A train whose
		 * occupied head is the short block between `zA` and `vA` meets its released tail at the switch
		 * `vA`. Trimming there would store a PathInfo that ends at a switch, and every later extension
		 * would fail the merge against it, so the trim is refused and the PathInfo is kept.
		 */
		@Test
		fun `refuses to trim to a switch`() {
			val held = reserveLongRoute(trainId)
			val vA = separatorAt(15, 8)
			// Keep kA and the short zA-vA block; release everything beyond the switch vA.
			val kept = held.filter { block -> zA() in block.ends() }
			held.filter { it !in kept }.forEach { block ->
				block.cancelPathSetup(requireNotNull(block.reservedFrom) { "a reserved block has a reservedFrom" })
				assertThat(registry.unregisterBlock(trainId, block)).isTrue()
			}
			val before = registry.getPathInfo(trainId)

			val trimmed = registry.trimPathInfoTo(trainId, vA)

			assertThat(trimmed).isFalse()
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(before)
		}

		/**
		 * Issue #1067 gap 2: `doA1` faces WEST on the `A -> B` direction — Issue #566's rear-facing
		 * rule deliberately leaves it at STOP even on a granted route that runs past it. A trim that
		 * accepted it as the new PathInfo end would store an end no future route can start from: G4
		 * refuses every route starting at a rear-facing signal, reproducing the stall this trim
		 * exists to prevent.
		 */
		@Test
		fun `refuses to trim to a signal facing away from the train`() {
			val held = reserveLongRoute(trainId)
			val vA = separatorAt(15, 8)
			val doA1 = separatorAt(16, 8)
			// Keep only the short vA-doA1 block; release everything beyond doA1.
			val head = held.first { block -> vA in block.ends() && doA1 in block.ends() }
			held.filter { it != head }.forEach { block ->
				block.cancelPathSetup(requireNotNull(block.reservedFrom) { "a reserved block has a reservedFrom" })
				assertThat(registry.unregisterBlock(trainId, block)).isTrue()
			}
			val before = registry.getPathInfo(trainId)

			val trimmed = registry.trimPathInfoTo(trainId, doA1)

			assertThat(trimmed).isFalse()
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(before)
		}

		@Test
		fun `a train without a PathInfo has nothing to trim`() {
			assertThat(registry.trimPathInfoTo("ghost", zA())).isFalse()
		}
	}

	/** @since Issue #1067 gap 1 */
	@Nested
	inner class TrimPathInfoToHeldBlocks {
		private val trainId = "trimHeldBlocksTrain"

		@Test
		fun `finds the boundary itself and trims to it`() {
			val held = reserveLongRoute(trainId)
			releaseAllBut(trainId, held, keptHead(held))

			val trimmed = registry.trimPathInfoToHeldBlocks(trainId)

			assertThat(trimmed).isTrue()
			assertThat(registry.getPathInfo(trainId)!!.target).isEqualTo(zA())
			assertThat(registry.isPathExtendedBeyond(trainId, zA())).isFalse()
		}

		@Test
		fun `a second call changes nothing`() {
			val held = reserveLongRoute(trainId)
			releaseAllBut(trainId, held, keptHead(held))
			registry.trimPathInfoToHeldBlocks(trainId)
			val trimmed = registry.getPathInfo(trainId)

			assertThat(registry.trimPathInfoToHeldBlocks(trainId)).isTrue()
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(trimmed)
		}

		@Test
		fun `a fully held route is left as it is`() {
			reserveLongRoute(trainId)
			val before = registry.getPathInfo(trainId)

			assertThat(registry.trimPathInfoToHeldBlocks(trainId)).isTrue()
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(before)
		}

		@Test
		fun `refuses when the held blocks end at a switch`() {
			val held = reserveLongRoute(trainId)
			releaseAllBut(trainId, held, held.filter { block -> zA() in block.ends() })
			val before = registry.getPathInfo(trainId)

			assertThat(registry.trimPathInfoToHeldBlocks(trainId)).isFalse()
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(before)
		}

		@Test
		fun `a train holding no block on its PathInfo has nothing to trim`() {
			val held = reserveLongRoute(trainId)
			releaseAllBut(trainId, held, emptyList())
			val before = registry.getPathInfo(trainId)

			assertThat(registry.trimPathInfoToHeldBlocks(trainId)).isFalse()
			assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(before)
		}

		@Test
		fun `a train without a PathInfo has nothing to trim`() {
			assertThat(registry.trimPathInfoToHeldBlocks("ghost")).isFalse()
		}
	}

	/** @since Issue #1067 */
	@Nested
	inner class PathInfoEnds {
		private val trainId = "pathInfoEndTrain"

		@Test
		fun `the end after the head is its exit signal and the block beyond it`() {
			val held = reserveLongRoute(trainId)

			val end = registry.pathInfoEndAfter(trainId, listOf(held.first()))

			assertThat(end!!.boundary).isEqualTo(zA())
			val nextBlock = requireNotNull(end.nextBlock) { "a block follows zA on the A -> B route" }
			assertThat(nextBlock).isNotEqualTo(held.first())
			assertThat(zA() in nextBlock.ends()).isTrue()
			assertThat(registry.isValidPathInfoEnd(end.boundary, nextBlock)).isTrue()
		}

		@Test
		fun `the end after the last block is the target with no block beyond it`() {
			val held = reserveLongRoute(trainId)

			val end = registry.pathInfoEndAfter(trainId, listOf(held.last()))

			assertThat(end!!.boundary).isEqualTo(registry.getPathInfo(trainId)!!.target)
			assertThat(end.nextBlock).isNull()
		}

		/**
		 * PR #1068 review: a merged circular route may pass a block twice. The last occurrence can then be
		 * ahead of the train, and a trim there would drop track it still holds, so there is no end.
		 */
		@Test
		fun `no end when a kept block appears twice on the PathInfo`() {
			val held = reserveLongRoute(trainId)
			val stored = requireNotNull(registry.getPathInfo(trainId))
			val head = held.first()
			val revisiting = ArrayPath(simulationContext)
			stored.reservedPath.forEach { revisiting.add(it) }
			stored.reservedPath
				.filter { element -> (element as? TrackSection)?.getTrackBlock() == head }
				.forEach { revisiting.add(it) }
			registry.registerPathInfo("revisitingTrain", stored.copy(reservedPath = revisiting))

			assertThat(registry.pathInfoEndAfter("revisitingTrain", listOf(head))).isNull()
			assertThat(registry.pathInfoEndAfter(trainId, listOf(head))).isNotNull()
		}

		/**
		 * PR #1068 review: a route that turns back at a separator re-enters the block it came from right
		 * after that separator. The two passes are separate places, so there is no end either.
		 */
		@Test
		fun `no end when a kept block appears again right after a separator`() {
			val held = reserveLongRoute(trainId)
			val stored = requireNotNull(registry.getPathInfo(trainId))
			val head = held.first()
			val elements = stored.reservedPath.toList()
			val headSections = elements.filter { element -> (element as? TrackSection)?.getTrackBlock() == head }
			val separatorBeforeHead = elements[elements.indexOf(headSections.first()) - 1]
			val separatorAfterHead = elements[elements.indexOf(headSections.last()) + 1]
			// separatorBeforeHead, head, separatorAfterHead, head again, separatorBeforeHead: the second
			// pass is followed by a separator, so "the last occurrence" would give an end.
			val turningBack = ArrayPath(simulationContext)
			turningBack.add(separatorBeforeHead)
			headSections.forEach { turningBack.add(it) }
			turningBack.add(separatorAfterHead)
			headSections.reversed().forEach { turningBack.add(it) }
			turningBack.add(separatorBeforeHead)
			registry.registerPathInfo("turningTrain", stored.copy(reservedPath = turningBack))

			assertThat(registry.pathInfoEndAfter("turningTrain", listOf(head))).isNull()
		}

		@Test
		fun `no end when no kept block lies on the PathInfo or there is no PathInfo`() {
			reserveLongRoute(trainId)

			assertThat(registry.pathInfoEndAfter(trainId, emptyList())).isNull()
			assertThat(registry.pathInfoEndAfter("ghost", blocks)).isNull()
		}

		/** `doA1` faces WEST, away from an `A -> B` train: valid only when the direction is unknown. */
		@Test
		fun `a signal facing away from the next block is not a valid end`() {
			val held = reserveLongRoute(trainId)
			val doA1 = separatorAt(16, 8)
			val head = held.first { block -> separatorAt(15, 8) in block.ends() && doA1 in block.ends() }

			val end = registry.pathInfoEndAfter(trainId, listOf(head))

			assertThat(end!!.boundary).isEqualTo(doA1)
			assertThat(registry.isValidPathInfoEnd(doA1, end.nextBlock)).isFalse()
			assertThat(registry.isValidPathInfoEnd(doA1)).isTrue()
		}

		@Test
		fun `a switch is never a valid end and an InOut always is`() {
			val held = reserveLongRoute(trainId)
			val inOutA = simulationContext.getInOuts().single { it.name == "A" }

			assertThat(registry.isValidPathInfoEnd(separatorAt(15, 8), held[1])).isFalse()
			assertThat(registry.isValidPathInfoEnd(inOutA, held.first())).isTrue()
		}
	}

	private fun separatorAt(
		x: Int,
		y: Int
	): DynamicPathSeparator = simulationContext.separatorAt(x, y)

	private fun zA(): DynamicPathSeparator = separatorAt(14, 8)

	/** Reserves InOut A -> InOut B for [trainId] and returns the reserved blocks, head (kA) first. */
	private fun reserveLongRoute(trainId: String): List<DynamicTrackBlock> {
		val inOuts = simulationContext.getInOuts()
		val result =
			simulationContext.getRoutingServices().getPathReservationService().reservePath(
				trainId,
				inOuts.single { it.name == "A" },
				inOuts.single { it.name == "B" }
			)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		return registry.getBlocks(trainId)
	}

	/** The head block `kA`: it touches `zA` and no switch. */
	private fun keptHead(held: List<DynamicTrackBlock>): List<DynamicTrackBlock> =
		listOf(held.first { block -> zA() in block.ends() && block.ends().none { it is DynamicRailSwitch } })

	/**
	 * Releases every block of [held] not in [kept] the way `RegistryPartialRouteReleaser` does: free the
	 * block first, because `unregisterBlock` only accepts a FREE block.
	 */
	private fun releaseAllBut(
		trainId: String,
		held: List<DynamicTrackBlock>,
		kept: List<DynamicTrackBlock>
	) {
		held.filter { it !in kept }.forEach { block ->
			block.cancelPathSetup(requireNotNull(block.reservedFrom) { "a reserved block has a reservedFrom" })
			assertThat(registry.unregisterBlock(trainId, block)).isTrue()
		}
	}
}
