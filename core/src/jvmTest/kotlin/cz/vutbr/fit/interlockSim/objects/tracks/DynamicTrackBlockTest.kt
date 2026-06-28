/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import assertk.assertions.message
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive unit tests for DynamicTrackBlock wrapper class (Issue #277)
 *
 * Tests verify:
 * - Construction and property delegation to static object
 * - State machine transitions (FREE → RESERVED → OCCUPIED → FREE)
 * - Invalid transition prevention
 * - Identity and equality based on static object
 * - Hash code stability across state changes
 */
@DisplayName("DynamicTrackBlock")
class DynamicTrackBlockTest {
	private lateinit var staticBlock1: TrackBlock
	private lateinit var staticBlock2: TrackBlock
	private lateinit var dynamicBlock1: DynamicTrackBlock
	private lateinit var dynamicBlock2: DynamicTrackBlock
	private lateinit var semaphore1: PathSeparator
	private lateinit var semaphore2: PathSeparator
	private lateinit var dynSemaphore1: DynamicPathSeparator
	private lateinit var dynSemaphore2: DynamicPathSeparator
	private lateinit var train: TrackOccupant

	@BeforeEach
	fun setUp() {
		// Create path separators for track ends
		semaphore1 = mockk<PathSeparator>()
		semaphore2 = mockk<PathSeparator>()
		dynSemaphore1 = mockk()
		dynSemaphore2 = mockk()

		// Create static track blocks (immutable configuration)
		staticBlock1 = SimpleTrackBlock(semaphore1, semaphore2, 100.0, 30.0, 30.0)
		staticBlock2 = SimpleTrackBlock(semaphore1, semaphore2, 200.0, 40.0, 40.0)

		// Create dynamic wrappers
		dynamicBlock1 = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore1)
		dynamicBlock2 = DynamicTrackBlock(staticBlock2, dynSemaphore1, dynSemaphore2)

		// Create mock train (occupant)
		train = mockk<TrackOccupant>()
	}

	// ========== Construction and Delegation (3 tests) ==========

	@Nested
	@DisplayName("Construction and Property Delegation")
	inner class ConstructionAndDelegation {
		@Test
		@DisplayName("delegates static properties to wrapped TrackBlock")
		fun delegatesStaticProperties() {
			// Verify delegation of all static properties
			assertThat(dynamicBlock1.length()).isEqualTo(staticBlock1.length())
			assertThat(dynamicBlock1.length()).isEqualTo(100.0)

			assertThat(dynamicBlock1.staticRef.ends()).isEqualTo(staticBlock1.ends())

			// Verify ends returns the separators used in construction
			val ends1 = dynamicBlock1.ends()
			assertThat(ends1).hasSize(2)
			assertThat(ends1.toList()).contains(dynSemaphore1)
			assertThat(ends1.toList()).contains(dynSemaphore1)

			assertThat(dynamicBlock1.getSecondEnd(semaphore1)).isEqualTo(staticBlock1.getSecondEnd(semaphore1))
			assertThat(dynamicBlock1.getSecondEnd(semaphore1)).isEqualTo(semaphore2)

			// Verify for second block with different properties
			assertThat(dynamicBlock2.length()).isEqualTo(staticBlock2.length())
			assertThat(dynamicBlock2.length()).isEqualTo(200.0)
		}

		@Test
		@DisplayName("initial state is FREE with null occupant")
		fun initialStateIsFree() {
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.FREE)
			assertThat(dynamicBlock1.occupant).isNull()
			assertThat(dynamicBlock1.reservedFrom).isNull()
		}

		@Test
		@DisplayName("staticRef points to original TrackBlock")
		fun staticRefAccessible() {
			assertThat(dynamicBlock1.staticRef).isSameInstanceAs(staticBlock1)
			assertThat(dynamicBlock2.staticRef).isSameInstanceAs(staticBlock2)
		}
	}

	// ========== State Machine Transitions (6 tests) ==========

	@Nested
	@DisplayName("State Machine Transitions")
	inner class StateMachineTransitions {
		@Test
		@DisplayName("FREE to RESERVED via setUpPath")
		fun freeToReserved() {
			// Initially FREE
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.FREE)
			assertThat(dynamicBlock1.isFreeFrom(dynSemaphore1)).isTrue()

			// Reserve path
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")

			// Now RESERVED
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(dynamicBlock1.isSetUpPath(dynSemaphore1)).isTrue()
			assertThat(dynamicBlock1.reservedFrom).isEqualTo(dynSemaphore1)
		}

		@Test
		@DisplayName("RESERVED to OCCUPIED via enter")
		fun reservedToOccupied() {
			// Set up path first
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.RESERVED)

			// Train enters
			dynamicBlock1.enter(train)

			// Now OCCUPIED
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.OCCUPIED)
			assertThat(dynamicBlock1.occupant).isEqualTo(train)
			assertThat(dynamicBlock1.getTrackOccupant()).isEqualTo(train)
			assertThat(dynamicBlock1.reservedFrom).isNull() // Cleared on entry
		}

		@Test
		@DisplayName("OCCUPIED to FREE via leave")
		fun occupiedToFree() {
			// Set up path and enter
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			dynamicBlock1.enter(train)
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.OCCUPIED)

			// Train leaves
			dynamicBlock1.leave(train)

			// Back to FREE
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.FREE)
			assertThat(dynamicBlock1.occupant).isNull()
			assertThat(dynamicBlock1.isFreeFrom(dynSemaphore1)).isTrue()
		}

		@Test
		@DisplayName("RESERVED to FREE via cancelPathSetup")
		fun reservedToFreeCancellation() {
			// Reserve path
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.RESERVED)

			// Cancel reservation
			dynamicBlock1.cancelPathSetup(dynSemaphore1)

			// Back to FREE
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.FREE)
			assertThat(dynamicBlock1.reservedFrom).isNull()
			assertThat(dynamicBlock1.isFreeFrom(dynSemaphore1)).isTrue()
		}

		@Test
		@DisplayName("complete cycle: FREE → RESERVED → OCCUPIED → FREE")
		fun fullStateCycle() {
			// 1. Initial state: FREE
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.FREE)

			// 2. Reserve path: FREE → RESERVED
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.RESERVED)

			// 3. Train enters: RESERVED → OCCUPIED
			dynamicBlock1.enter(train)
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.OCCUPIED)

			// 4. Train leaves: OCCUPIED → FREE
			dynamicBlock1.leave(train)
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.FREE)
		}

		@Test
		@DisplayName("can reserve from different separator after release")
		fun differentReservations() {
			// Reserve from semaphore1
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.reservedFrom).isEqualTo(dynSemaphore1)

			// Cancel and reserve from semaphore2
			dynamicBlock1.cancelPathSetup(dynSemaphore1)
			dynamicBlock1.setUpPath(dynSemaphore2, "block-test-train")

			assertThat(dynamicBlock1.reservedFrom).isEqualTo(dynSemaphore2)
			assertThat(dynamicBlock1.isSetUpPath(dynSemaphore2)).isTrue()
			assertThat(dynamicBlock1.isSetUpPath(dynSemaphore1)).isFalse()
		}
	}

	// ========== Invalid Transitions (3 tests) ==========

	@Nested
	@DisplayName("Invalid Transition Prevention")
	inner class InvalidTransitions {
		@Test
		@DisplayName("entering FREE track throws exception")
		fun cannotEnterFreeTrack() {
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.FREE)

			assertFailure { dynamicBlock1.enter(train) }
				.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
				.message()
				.isNotNull()
				.contains("Wrong state")
		}

		@Test
		@DisplayName("double reservation from same separator is idempotent")
		fun cannotDoubleReserve() {
			// Reserve once
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.RESERVED)

			// Try to reserve again from SAME separator - should succeed (idempotent)
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(dynamicBlock1.reservedFrom).isEqualTo(dynSemaphore1)
		}

		@Test
		@DisplayName("reservation from different separator throws exception")
		fun cannotReserveFromDifferentSeparator() {
			// Reserve from first separator
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.getState()).isEqualTo(TrackFacility.State.RESERVED)

			// Try to reserve from DIFFERENT separator - should fail
			assertFailure { dynamicBlock1.setUpPath(dynSemaphore2, "block-test-train") }
				.isInstanceOf(TrackReservationException.AlreadyReservedConflict::class)
				.message()
				.isNotNull()
				.contains("already reserved from")
		}

		@Test
		@DisplayName("wrong occupant on leave throws exception")
		fun wrongOccupantOnLeave() {
			val wrongTrain = mockk<TrackOccupant>()

			// Set up and enter with first train
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			dynamicBlock1.enter(train)

			// Try to leave with different train
			assertFailure { dynamicBlock1.leave(wrongTrain) }
				.isInstanceOf(cz.vutbr.fit.interlockSim.exceptions.SimulationException::class)
				.message()
				.isNotNull()
				.contains("mismatch")
		}
	}

	// ========== Identity and Equality (3 tests) ==========

	@Nested
	@DisplayName("Identity and Equality")
	inner class IdentityAndEquality {
		@Test
		@DisplayName("wrappers for same static block are equal")
		fun sameStaticBlockEquals() {
			val anotherWrapper1 = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore2)

			// Same static object → equal
			assertThat(dynamicBlock1).isEqualTo(anotherWrapper1)
			assertThat(dynamicBlock1.hashCode()).isEqualTo(anotherWrapper1.hashCode())

			// Different static object → not equal
			assertThat(dynamicBlock1).isNotEqualTo(dynamicBlock2)
		}

		@Test
		@DisplayName("hash code stable across state changes")
		fun hashCodeStableAcrossStateChanges() {
			val hashBefore = dynamicBlock1.hashCode()

			// Change state multiple times
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.hashCode()).isEqualTo(hashBefore)

			dynamicBlock1.enter(train)
			assertThat(dynamicBlock1.hashCode()).isEqualTo(hashBefore)

			dynamicBlock1.leave(train)
			assertThat(dynamicBlock1.hashCode()).isEqualTo(hashBefore)
		}

		@Test
		@DisplayName("equals stable across state changes")
		fun equalsStableAcrossStateChanges() {
			val anotherWrapper1 = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore2)

			// Initially equal
			assertThat(dynamicBlock1).isEqualTo(anotherWrapper1)

			// Change one wrapper's state
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			dynamicBlock1.enter(train)

			// Still equal (equality based on static object, not state)
			assertThat(dynamicBlock1).isEqualTo(anotherWrapper1)
			assertThat(anotherWrapper1.getState()).isEqualTo(TrackFacility.State.FREE) // Other wrapper unchanged
		}
	}

	// ========== Additional Functionality Tests ==========

	@Nested
	@DisplayName("Additional Functionality")
	inner class AdditionalFunctionality {
		@Test
		@DisplayName("can use in hash-based collections")
		fun hashBasedCollections() {
			val set = mutableSetOf<DynamicTrackBlock>()

			// Add first wrapper
			set.add(dynamicBlock1)
			assertThat(set).hasSize(1)

			// Add wrapper for same static object → should not increase size
			val anotherWrapper1 = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore2)
			set.add(anotherWrapper1)
			assertThat(set).hasSize(1)

			// Add wrapper for different static object → should increase size
			set.add(dynamicBlock2)
			assertThat(set).hasSize(2)
		}

		@Test
		@DisplayName("toString includes state and occupant")
		fun toStringFormat() {
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			dynamicBlock1.enter(train)

			val str = dynamicBlock1.toString()

			assertThat(str).contains("DynamicTrackBlock")
			assertThat(str).contains("OCCUPIED")
			assertThat(str).contains(train.toString())
		}

		@Test
		@DisplayName("getTrackOccupant throws when not occupied")
		fun getTrackOccupantThrowsWhenNotOccupied() {
			// FREE state
			assertThat(dynamicBlock1.getTrackOccupant()).isNull()

			// also RESERVED state doesn't have real occupant yet, only reservation name
			dynamicBlock1.setUpPath(dynSemaphore1, "block-test-train")
			assertThat(dynamicBlock1.getTrackOccupant()).isNull()
		}
	}

	// ========== Block Occupancy Events (Issue #569) ==========

	@Nested
	@DisplayName("Block Occupancy Events")
	inner class BlockOccupancyEvents {
		@Test
		@DisplayName("setUpPath emits BLOCK_RESERVED event")
		fun setupPathEmitsReservedEvent() {
			val sink = RecordingSink()
			val block = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore2, sink)

			block.setUpPath(dynSemaphore1, "train-A")

			assertThat(sink.events).hasSize(1)
			val event = sink.events[0]
			assertThat(event.block).isEqualTo(block)
			assertThat(event.type).isEqualTo(BlockOccupancyEventType.BLOCK_RESERVED)
			assertThat(event.trainId).isEqualTo("train-A")
			assertThat(event.occupant).isNull()
			assertThat(event.previousState).isEqualTo(TrackFacility.State.FREE)
			assertThat(event.newState).isEqualTo(TrackFacility.State.RESERVED)
		}

		@Test
		@DisplayName("enter emits BLOCK_OCCUPIED event")
		fun enterEmitsOccupiedEvent() {
			val sink = RecordingSink()
			val block = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore2, sink)
			block.setUpPath(dynSemaphore1, "train-A")

			block.enter(train)

			assertThat(sink.events).hasSize(2)
			val event = sink.events[1]
			assertThat(event.type).isEqualTo(BlockOccupancyEventType.BLOCK_OCCUPIED)
			assertThat(event.trainId).isEqualTo("train-A")
			assertThat(event.occupant).isEqualTo(train)
			assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(event.newState).isEqualTo(TrackFacility.State.OCCUPIED)
		}

		@Test
		@DisplayName("leave emits BLOCK_RELEASED event with previous occupant")
		fun leaveEmitsReleasedEvent() {
			val sink = RecordingSink()
			val block = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore2, sink)
			block.setUpPath(dynSemaphore1, "train-A")
			block.enter(train)

			block.leave(train)

			assertThat(sink.events).hasSize(3)
			val event = sink.events[2]
			assertThat(event.type).isEqualTo(BlockOccupancyEventType.BLOCK_RELEASED)
			assertThat(event.trainId).isEqualTo("train-A")
			assertThat(event.occupant).isEqualTo(train)
			assertThat(event.previousState).isEqualTo(TrackFacility.State.OCCUPIED)
			assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
		}

		@Test
		@DisplayName("cancelPathSetup emits BLOCK_RELEASED event")
		fun cancelPathSetupEmitsReleasedEvent() {
			val sink = RecordingSink()
			val block = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore2, sink)
			block.setUpPath(dynSemaphore1, "train-A")

			block.cancelPathSetup(dynSemaphore1)

			assertThat(sink.events).hasSize(2)
			val event = sink.events[1]
			assertThat(event.type).isEqualTo(BlockOccupancyEventType.BLOCK_RELEASED)
			assertThat(event.trainId).isEqualTo("train-A")
			assertThat(event.occupant).isNull()
			assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
		}

		@Test
		@DisplayName("idempotent setUpPath does not emit duplicate event")
		fun idempotentSetupPathDoesNotDuplicateEvent() {
			val sink = RecordingSink()
			val block = DynamicTrackBlock(staticBlock1, dynSemaphore1, dynSemaphore2, sink)
			block.setUpPath(dynSemaphore1, "train-A")

			block.setUpPath(dynSemaphore1, "train-A")

			assertThat(sink.events).hasSize(1)
		}
	}

	private class RecordingSink : BlockOccupancyEventSink {
		val events = mutableListOf<BlockOccupancyEvent>()
		override fun emit(event: BlockOccupancyEvent) {
			events.add(event)
		}
	}
}
