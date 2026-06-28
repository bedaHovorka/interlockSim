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
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.InputStream

/**
 * Comprehensive test suite for PathReservationRegistry atomic operations.
 *
 * ## Test Coverage
 *
 * - ✅ registerAtomic() success
 * - ✅ registerAtomic() conflict detection
 * - ✅ registerAtomic() atomicity (no partial registration)
 * - ✅ registerAtomic() idempotent (same train can re-register)
 * - ✅ Deprecated register() backward compatibility
 * - ✅ Bidirectional mapping integrity
 * - ✅ Unregister operations
 *
 * @since Issue #292 Phase 2 Enhancement (Plan Phase 2)
 */
class PathReservationRegistryTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var registry: PathReservationRegistry
	private lateinit var blocks: List<DynamicTrackBlock>

	@BeforeEach
	fun setUp() {
		// Load vyhybna.xml to get real DynamicTrackBlock instances
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		val simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

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
	inner class BackwardCompatibility {
		@Test
		fun `deprecated register() succeeds when no conflicts`() {
			// Act
			@Suppress("DEPRECATION")
			registry.register("train1", blocks)

			// Assert
			val registeredBlocks = registry.getBlocks("train1")
			assertThat(registeredBlocks).containsExactly(*blocks.toTypedArray())
		}

		@Test
		fun `deprecated register() throws IllegalStateException on conflict`() {
			// Arrange
			registry.registerAtomic("train1", blocks)

			// Act & Assert
			assertFailure {
				@Suppress("DEPRECATION")
				registry.register("train2", blocks)
			}.isInstanceOf(IllegalStateException::class)
				.hasMessage(
					"Block ${blocks.first()} already reserved by train1 (attempted reservation by train2)"
				)
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

	private class FakeTrackOccupant(
		override val name: String
	) : TrackOccupant {
		override fun distanceToSemaphore(): Double = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}
}
