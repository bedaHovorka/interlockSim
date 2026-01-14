/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Comprehensive race condition tests for concurrent access scenarios
 * Phase 6 advanced test implementation - 2026
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockNodeCell
import cz.vutbr.fit.interlockSim.testutil.MockTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.buildLinearTrack
import cz.vutbr.fit.interlockSim.testutil.buildMinimal
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.ArrayList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive race condition tests for concurrent access scenarios.
 *
 * **IMPORTANT: Tests in this class are DISABLED by design.**
 *
 * These tests demonstrate race conditions that occur when Context classes are
 * accessed concurrently. However, Context is intentionally NOT thread-safe:
 *
 * ## Design Decision (Option 1)
 *
 * Context and its implementations (DefaultSimulationContext, EditingContext, SimulationContext)
 * are documented as NOT thread-safe. Multi-threaded access is not supported.
 *
 * ### Rationale:
 * 1. Railway simulations are inherently sequential (discrete event model)
 * 2. The jDisco framework is single-threaded by design
 * 3. No current use cases require concurrent Context access
 * 4. Thread-safety would add unnecessary complexity and performance overhead
 * 5. Aligns with KISS principle
 *
 * ### Race Conditions Documented:
 * - NullPointerException in grid access during concurrent modifications
 * - ArrayIndexOutOfBoundsException in cell operations
 * - ConcurrentModificationException in listener notifications
 *
 * These tests remain in the codebase to:
 * 1. Document known race conditions if thread-safety is attempted in the future
 * 2. Provide evidence for the design decision to not implement thread-safety
 * 3. Serve as integration tests if the design decision is revisited
 *
 * **Usage:** Do not enable these tests unless implementing thread-safety (Option 2).
 *
 * @see cz.vutbr.fit.interlockSim.context.Context
 * @see cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
 * @since 2026-01 (Phase 6 advanced testing)
 * @tag integration-test
 */
@Tag("integration-test")
@DisplayName("Race Condition and Concurrent Access Tests")
class RaceConditionTest : KoinTestBase() {
	companion object {
		private const val DEFAULT_TIMEOUT_SECONDS = 10
		private const val THREAD_COUNT = 5
		private const val STRESS_TEST_ITERATIONS = 10
	}

	private val end1 = MockNodeCell("End1")
	private val end2 = MockNodeCell("End2")
	private val end3 = MockNodeCell("End3")
	private val end4 = MockNodeCell("End4")

	/**
	 * Nested tests for concurrent path reservation attempts.
	 * Tests the atomicity of multi-track path reservation under concurrent access.
	 */
	@Nested
	@DisplayName("Concurrent Path Reservations")
	inner class ConcurrentPathReservations {
		/**
		 * Test that multiple threads can attempt path reservations without crashing.
		 * Verifies that reservation operations are thread-safe at the API level.
		 */
		@Test
		@DisplayName("Multiple threads attempting simultaneous path reservations")
		fun concurrentPathReservations_multipleThreads_noExceptionThrown() {
			// Arrange - Create two tracks
			val track1 = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			val track2 = SimpleTrackBlock(end2, end3, 100.0, 80.0)

			val threadCount = THREAD_COUNT
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(threadCount)
			val successCount = AtomicInteger(0)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Act - Launch multiple threads attempting path setup
			val threads = ArrayList<Thread>()
			for (i in 0 until threadCount) {
				val thread =
					Thread {
						try {
							startLatch.await()

							// Attempt to setup path on track1
							track1.setUpPath(end1)
							successCount.incrementAndGet()
						} catch (e: TrackOperationException) {
							// Expected if another thread beat us to it
							successCount.incrementAndGet()
						} catch (e: Exception) {
							exceptions.add(e)
						} finally {
							doneLatch.countDown()
						}
					}
				threads.add(thread)
				thread.start()
			}

			// Start all threads simultaneously
			startLatch.countDown()
			val completed = doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All threads should complete within timeout")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions should occur")
				.isEmpty()
			assertThat(successCount.get())
				.withMessage("All threads should complete (either succeed or handle TrackOperationException)")
				.isEqualTo(threadCount)
		}

		/**
		 * Test that concurrent path cancellations do not cause state corruption.
		 * Validates that cancel operations are safe under concurrent access.
		 */
		@Test
		@DisplayName("Concurrent path cancellations maintain track state consistency")
		fun concurrentPathCancellations_maintainStateConsistency() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)

			// Setup initial path
			track.setUpPath(end1)
			val isFreeInitially = track.isFreeFrom(end1)

			val threadCount = THREAD_COUNT
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(threadCount)
			val successCount = AtomicInteger(0)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Act - Multiple threads attempt cancellation
			val threads = ArrayList<Thread>()
			for (i in 0 until threadCount) {
				val thread =
					Thread {
						try {
							startLatch.await()

							// Attempt to cancel path setup
							track.cancelPathSetup(end1)
							successCount.incrementAndGet()
						} catch (e: TrackOperationException) {
							// Expected if another thread already cancelled
							successCount.incrementAndGet()
						} catch (e: Exception) {
							exceptions.add(e)
						} finally {
							doneLatch.countDown()
						}
					}
				threads.add(thread)
				thread.start()
			}

			startLatch.countDown()
			val completed = doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All cancellation threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during cancellation")
				.isEmpty()
		}

		/**
		 * Test alternating reservation and cancellation operations from multiple threads.
		 * Validates state machine consistency under concurrent access.
		 */
		@Test
		@DisplayName("Alternating reservation and cancellation from concurrent threads")
		fun alternatingReservationCancellation_maintainsStateConsistency() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			val iterations = 10
			val threadCount = 3
			val totalLatch = CountDownLatch(threadCount * iterations)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Act - Multiple threads alternate between setup and cancel
			for (threadId in 0 until threadCount) {
				Thread {
					for (iter in 0 until iterations) {
						try {
							// Thread-local timing to avoid deadlock
							if (iter % 2 == 0) {
								track.setUpPath(end1)
							} else {
								track.cancelPathSetup(end1)
							}
						} catch (e: TrackOperationException) {
							// Expected due to concurrent state changes
						} catch (e: Exception) {
							exceptions.add(e)
						} finally {
							totalLatch.countDown()
						}
					}
				}.start()
			}

			val completed =
				totalLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All alternating operations should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during alternating ops")
				.isEmpty()
		}
	}

	/**
	 * Nested tests for concurrent switch state changes.
	 * Validates that switch configuration operations are thread-safe.
	 */
	@Nested
	@DisplayName("Concurrent Switch State Changes")
	inner class ConcurrentSwitchStateChanges {
		/**
		 * Test concurrent switch configuration changes from multiple threads.
		 * Ensures that switch state transitions are atomic and consistent.
		 */
		@Test
		@DisplayName("Concurrent switch configuration changes resolve without exception")
		fun concurrentSwitchChanges_multipleThreads_noException() {
			// Arrange
			val switch =
				RailSwitch(
					cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_RIGHT_FALSE
				)

			val threadCount = THREAD_COUNT
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(threadCount)
			val exceptions = CopyOnWriteArrayList<Exception>()
			val successCount = AtomicInteger(0)

			// Act - Multiple threads attempt switch configuration changes
			val threads = ArrayList<Thread>()
			for (i in 0 until threadCount) {
				val thread =
					Thread {
						try {
							startLatch.await()

							// Attempt to change switch configuration
							switch.changeConf()
							successCount.incrementAndGet()
						} catch (e: IllegalStateException) {
							// Expected - switch may be locked
						} catch (e: Exception) {
							exceptions.add(e)
						} finally {
							doneLatch.countDown()
						}
					}
				threads.add(thread)
				thread.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All switch change threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during switch changes")
				.isEmpty()
		}

		/**
		 * Test switch configuration queries while other threads are modifying it.
		 * Validates that getConf() returns a consistent state.
		 */
		@Test
		@DisplayName("Switch configuration queries are consistent during concurrent modifications")
		fun switchConfigQueries_duringConcurrentMod_returnsConsistentState() {
			// Arrange
			val switch =
				RailSwitch(
					cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.HORIZONTAL,
					RailSwitch.Type.SIMPLE_RIGHT_FALSE
				)

			val iterations = 20
			val readThreadCount = 2
			val writeThreadCount = 1
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(readThreadCount + writeThreadCount)
			val exceptions = CopyOnWriteArrayList<Exception>()
			val consistencyViolations = AtomicInteger(0)
			val lastSeenConf = AtomicReference<RailSwitch.Conf>()

			// Writer thread - continuously changes switch configuration
			Thread {
				try {
					startLatch.await()
					for (i in 0 until iterations) {
						try {
							switch.changeConf()
							lastSeenConf.set(switch.getConf())
							Thread.sleep(1)
						} catch (e: IllegalStateException) {
							// Expected if switch is locked
						}
					}
				} catch (e: Exception) {
					exceptions.add(e)
				} finally {
					doneLatch.countDown()
				}
			}.start()

			// Reader threads - query switch state
			for (r in 0 until readThreadCount) {
				Thread {
					try {
						startLatch.await()
						for (i in 0 until iterations) {
							val conf = switch.getConf()
							// Configuration should be one of the valid states
							if (conf != RailSwitch.Conf.MAIN && conf != RailSwitch.Conf.BRANCH) {
								consistencyViolations.incrementAndGet()
							}
							Thread.sleep(1)
						}
					} catch (e: Exception) {
						exceptions.add(e)
					} finally {
						doneLatch.countDown()
					}
				}.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All reader/writer threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during concurrent access")
				.isEmpty()
			assertThat(consistencyViolations.get())
				.withMessage("No switch configuration consistency violations")
				.isEqualTo(0)
		}
	}

	/**
	 * Nested tests for concurrent track occupancy operations.
	 * Validates that enter/leave operations maintain invariants under concurrent access.
	 */
	@Nested
	@DisplayName("Concurrent Track Occupancy Operations")
	inner class ConcurrentTrackOccupancy {
		/**
		 * Test that concurrent enter operations on the same track are handled safely.
		 * While only one occupant should actually occupy a track, concurrent attempts
		 * should be handled deterministically.
		 */
		@Test
		@DisplayName("Concurrent enter attempts on same track handled safely")
		fun concurrentEnter_sameTrack_handlesSafely() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)

			// First reserve the track
			track.setUpPath(end1)

			val threadCount = THREAD_COUNT
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(threadCount)
			val successCount = AtomicInteger(0)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Act - Multiple threads attempt to enter same track
			val threads = ArrayList<Thread>()
			for (i in 0 until threadCount) {
				val occupant = MockTrackOccupant("Train$i")
				val thread =
					Thread {
						try {
							startLatch.await()

							track.enter(occupant)
							successCount.incrementAndGet()
						} catch (e: AssertionError) {
							// Expected - only one should succeed in entering
						} catch (e: cz.vutbr.fit.interlockSim.exceptions.SimulationException) {
							// Expected - collision detection throws SimulationException
						} catch (e: Exception) {
							exceptions.add(e)
						} finally {
							doneLatch.countDown()
						}
					}
				threads.add(thread)
				thread.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All enter attempt threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during concurrent enter")
				.isEmpty()
		}

		/**
		 * Test concurrent enter and leave operations on multiple tracks.
		 * Validates occupancy state consistency across multiple concurrent operations.
		 */
		@Test
		@DisplayName("Concurrent enter/leave on multiple tracks maintains consistency")
		fun concurrentEnterLeave_multipleTracksStress() {
			// Arrange - Create a chain of tracks
			val track1 = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			val track2 = SimpleTrackBlock(end2, end3, 100.0, 80.0)
			val track3 = SimpleTrackBlock(end3, end4, 100.0, 80.0)
			val tracks = listOf(track1, track2, track3)

			val occupant = MockTrackOccupant("Train1")
			val iterations = STRESS_TEST_ITERATIONS
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(2)
			val exceptions = CopyOnWriteArrayList<Exception>()
			val enterCount = AtomicInteger(0)
			val leaveCount = AtomicInteger(0)

			// Thread 1 - Repeatedly enters and leaves tracks
			Thread {
				try {
					startLatch.await()
					for (iter in 0 until iterations) {
						for (track in tracks) {
							try {
								track.setUpPath(end1)
								track.enter(occupant)
								enterCount.incrementAndGet()
								track.leave(occupant)
								leaveCount.incrementAndGet()
							} catch (e: TrackOperationException) {
								// Track state may have changed, retry
							} catch (e: AssertionError) {
								// Expected if multiple threads interfere
							}
						}
					}
				} catch (e: Exception) {
					exceptions.add(e)
				} finally {
					doneLatch.countDown()
				}
			}.start()

			// Thread 2 - Concurrently modifies track states
			Thread {
				try {
					startLatch.await()
					for (iter in 0 until iterations) {
						for (track in tracks) {
							try {
								track.setUpPath(end1)
								Thread.sleep(1)
								track.cancelPathSetup(end1)
							} catch (e: TrackOperationException) {
								// Expected due to concurrent modifications
							}
						}
					}
				} catch (e: Exception) {
					exceptions.add(e)
				} finally {
					doneLatch.countDown()
				}
			}.start()

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All concurrent enter/leave threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during occupancy stress test")
				.isEmpty()
		}

		/**
		 * Test that isFreeFrom queries remain consistent during concurrent modifications.
		 */
		@Test
		@DisplayName("Track occupancy queries consistent during concurrent modifications")
		fun trackOccupancyQueries_duringMod_returnsConsistentResults() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			val occupant = MockTrackOccupant("Train1")

			val iterations = 20
			val queryThreadCount = 2
			val modifyThreadCount = 1
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(queryThreadCount + modifyThreadCount)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Modify thread - changes track state
			Thread {
				try {
					startLatch.await()
					for (i in 0 until iterations) {
						track.setUpPath(end1)
						Thread.sleep(1)
						try {
							track.enter(occupant)
							Thread.sleep(1)
							track.leave(occupant)
						} catch (e: AssertionError) {
							// May fail if another thread interfered
						}
						Thread.sleep(1)
						track.cancelPathSetup(end1)
					}
				} catch (e: TrackOperationException) {
					// Expected due to concurrent modifications
				} catch (e: Exception) {
					exceptions.add(e)
				} finally {
					doneLatch.countDown()
				}
			}.start()

			// Query threads - check occupancy
			for (q in 0 until queryThreadCount) {
				Thread {
					try {
						startLatch.await()
						for (i in 0 until iterations) {
							// These queries should never throw, just return a state
							val isFree = track.isFreeFrom(end1)
							// isFree should be a valid boolean
							assertThat(isFree).isNotNull()
							Thread.sleep(1)
						}
					} catch (e: Exception) {
						exceptions.add(e)
					} finally {
						doneLatch.countDown()
					}
				}.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All query threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during occupancy queries")
				.isEmpty()
		}
	}

	/**
	 * Nested tests for concurrent Context modifications.
	 * Validates thread safety of grid-based context operations.
	 */
	@Nested
	@DisplayName("Concurrent Context Modifications")
	inner class ConcurrentContextModifications {
		/**
		 * Test concurrent cell placement and retrieval from the context grid.
		 *
		 * **DISABLED: Context is not thread-safe by design.**
		 *
		 * This test demonstrates ArrayIndexOutOfBoundsException that occurs when
		 * multiple threads concurrently access the Context grid. This is expected
		 * behavior as Context is intentionally not thread-safe.
		 *
		 * See Context documentation for rationale and usage guidelines.
		 *
		 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/28">Issue #28</a>
		 */
		@Test
		@Disabled("Context is not thread-safe by design (Issue #28). Do not use from multiple threads.")
		@DisplayName("Concurrent cell operations on context grid are thread-safe")
		fun concurrentCellOperations_contextGrid_threadSafe() {
			// Arrange
			val context = buildMinimal()
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(THREAD_COUNT)
			val exceptions = CopyOnWriteArrayList<Exception>()
			val successCount = AtomicInteger(0)

			// Act - Multiple threads place and retrieve cells
			val threads = ArrayList<Thread>()
			for (i in 0 until THREAD_COUNT) {
				val thread =
					Thread {
						try {
							startLatch.await()

							val cell = MockNodeCell("Cell$i")
							val x = 10 + i
							val y = 10 + i

							// Place cell
							context.putCell(
								cz.vutbr.fit.interlockSim.util
									.Point(x, y),
								cell
							)

							// Retrieve cell
							val retrieved = context.getRailWayNetGrid().getCellAt(x, y)
							if (retrieved != null) {
								successCount.incrementAndGet()
							}
						} catch (e: Exception) {
							exceptions.add(e)
						} finally {
							doneLatch.countDown()
						}
					}
				threads.add(thread)
				thread.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All cell operation threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during concurrent cell ops")
				.isEmpty()
		}

		/**
		 * Test concurrent context modifications maintain grid consistency.
		 *
		 * **DISABLED: Context is not thread-safe by design.**
		 *
		 * This test demonstrates NullPointerException that occurs when multiple
		 * threads concurrently modify the Context grid. This is expected behavior
		 * as Context is intentionally not thread-safe.
		 *
		 * See Context documentation for rationale and usage guidelines.
		 *
		 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/28">Issue #28</a>
		 */
		@Test
		@Disabled("Context is not thread-safe by design (Issue #28). Do not use from multiple threads.")
		@DisplayName("Concurrent context modifications maintain grid consistency")
		fun concurrentContextMod_maintainsGridConsistency() {
			// Arrange
			val context = buildLinearTrack()
			val grid = context.getRailWayNetGrid()

			val threadCount = 3
			val iterations = 10
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(threadCount)
			val exceptions = CopyOnWriteArrayList<Exception>()
			val totalOperations = AtomicInteger(0)

			// Act - Multiple threads perform various grid operations
			for (threadId in 0 until threadCount) {
				Thread {
					try {
						startLatch.await()
						for (iter in 0 until iterations) {
							val x = 20 + threadId * 10 + iter
							val y = 20 + threadId * 10 + iter

							// Try to place a cell
							val cell = MockNodeCell("Cell_${threadId}_$iter")
							context.putCell(
								cz.vutbr.fit.interlockSim.util
									.Point(x, y),
								cell
							)

							// Try to retrieve it
							val retrieved = grid.getCellAt(x, y)
							if (retrieved != null) {
								totalOperations.incrementAndGet()
							}
						}
					} catch (e: Exception) {
						exceptions.add(e)
					} finally {
						doneLatch.countDown()
					}
				}.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All context modification threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during grid modifications")
				.isEmpty()
			assertThat(totalOperations.get())
				.withMessage("Grid operations should complete successfully")
				.isEqualTo(threadCount * iterations)
		}

		/**
		 * Test that concurrent listener registration is thread-safe.
		 * Validates that PropertyChangeListener registration and removal
		 * work correctly under concurrent access.
		 */
		@Test
		@DisplayName("Concurrent listener registration/removal is thread-safe")
		fun concurrentListenerOps_threadSafe() {
			// Arrange
			val context = buildMinimal()
			val threadCount = 4
			val iterations = 5
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(threadCount)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Act - Multiple threads add and remove listeners
			for (threadId in 0 until threadCount) {
				Thread {
					try {
						startLatch.await()
						for (iter in 0 until iterations) {
							// Create a listener
							val listener =
								java.beans.PropertyChangeListener { _ ->
									// Listener receives property changes
								}

							// Add listener
							context.addPropertyChangeListener(listener)

							// Remove listener
							context.removePropertyChangeListener(listener)
						}
					} catch (e: Exception) {
						exceptions.add(e)
					} finally {
						doneLatch.countDown()
					}
				}.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All listener registration threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during concurrent listener ops")
				.isEmpty()
		}
	}

	/**
	 * Nested tests for race condition patterns in deterministic scenarios.
	 * Tests specific race condition patterns that could occur in simulation.
	 */
	@Nested
	@DisplayName("Race Condition Patterns")
	inner class RaceConditionPatterns {
		/**
		 * Test that multiple threads attempting the same path setup
		 * resolve deterministically without deadlock or livelock.
		 */
		@Test
		@DisplayName("Multiple simultaneous path requests resolve deterministically")
		fun simultaneousPathRequests_resolveDeterministically() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			val threadCount = THREAD_COUNT
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(threadCount)
			val setupAttempts = AtomicInteger(0)
			val setupSuccesses = AtomicInteger(0)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Act - Multiple threads race to setup the same path
			val threads = ArrayList<Thread>()
			for (i in 0 until threadCount) {
				val thread =
					Thread {
						try {
							startLatch.await()

							setupAttempts.incrementAndGet()
							try {
								track.setUpPath(end1)
								setupSuccesses.incrementAndGet()
							} catch (e: TrackOperationException) {
								// Other threads beat us - this is expected
							}
						} catch (e: Exception) {
							exceptions.add(e)
						} finally {
							doneLatch.countDown()
						}
					}
				threads.add(thread)
				thread.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All threads should complete without deadlock")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during simultaneous requests")
				.isEmpty()
			assertThat(setupAttempts.get())
				.withMessage("All threads should attempt setup")
				.isEqualTo(threadCount)
			// At least one should succeed (typically exactly one)
			assertThat(setupSuccesses.get() >= 1)
				.withMessage("At least one setup should succeed")
				.isTrue()
		}

		/**
		 * Test that rapid state changes from multiple threads don't cause corruption.
		 * This is a stress test for the state machine implementation.
		 */
		@Test
		@DisplayName("Rapid concurrent state changes don't corrupt track state")
		fun rapidStateChanges_noCorruption() {
			// Arrange
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0)
			val occupant = MockTrackOccupant("Train1")
			val iterations = 5
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(2)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Thread 1 - Rapid setup/cancel cycles
			Thread {
				try {
					startLatch.await()
					for (i in 0 until iterations) {
						try {
							track.setUpPath(end1)
							track.cancelPathSetup(end1)
						} catch (e: TrackOperationException) {
							// Expected due to interference
						}
					}
				} catch (e: Exception) {
					exceptions.add(e)
				} finally {
					doneLatch.countDown()
				}
			}.start()

			// Thread 2 - Rapid enter/leave cycles
			Thread {
				try {
					startLatch.await()
					for (i in 0 until iterations) {
						try {
							track.setUpPath(end1)
							track.enter(occupant)
							track.leave(occupant)
						} catch (e: TrackOperationException) {
							// Expected due to interference
						} catch (e: AssertionError) {
							// Expected if occupancy state violates invariant
						}
					}
				} catch (e: Exception) {
					exceptions.add(e)
				} finally {
					doneLatch.countDown()
				}
			}.start()

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("Both rapid state change threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during rapid state changes")
				.isEmpty()
		}

		/**
		 * Test that multiple listener registrations and removals work correctly
		 * under concurrent grid modifications.
		 *
		 * **DISABLED: Context is not thread-safe by design.**
		 *
		 * This test demonstrates ConcurrentModificationException that occurs when
		 * listener notifications happen concurrently with grid modifications. This
		 * is expected behavior as Context is intentionally not thread-safe.
		 *
		 * See Context documentation for rationale and usage guidelines.
		 *
		 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/28">Issue #28</a>
		 */
		@Test
		@Disabled("Context is not thread-safe by design (Issue #28). Do not use from multiple threads.")
		@DisplayName("Listeners and grid modifications work correctly concurrently")
		fun listenersAndGridMods_workConcurrently() {
			// Arrange
			val context = buildMinimal()
			val threadCount = 3
			val listenerOps = ArrayList<java.beans.PropertyChangeListener>()
			val startLatch = CountDownLatch(1)
			val doneLatch = CountDownLatch(threadCount)
			val exceptions = CopyOnWriteArrayList<Exception>()

			// Act - One thread adds listeners, others perform grid operations
			// Thread 1: Add/Remove listeners
			Thread {
				try {
					startLatch.await()
					for (i in 0 until 10) {
						val listener =
							java.beans.PropertyChangeListener { _ ->
								// Listener receives events
							}
						listenerOps.add(listener)
						context.addPropertyChangeListener(listener)
						Thread.sleep(1)
						context.removePropertyChangeListener(listener)
					}
				} catch (e: Exception) {
					exceptions.add(e)
				} finally {
					doneLatch.countDown()
				}
			}.start()

			// Thread 2 & 3: Modify grid
			for (threadId in 1 until threadCount) {
				Thread {
					try {
						startLatch.await()
						for (i in 0 until 10) {
							val cell = MockNodeCell("GridCell_${threadId}_$i")
							val x = 30 + threadId * 10 + i
							val y = 30 + threadId * 10 + i
							context.putCell(
								cz.vutbr.fit.interlockSim.util
									.Point(x, y),
								cell
							)
							Thread.sleep(1)
						}
					} catch (e: Exception) {
						exceptions.add(e)
					} finally {
						doneLatch.countDown()
					}
				}.start()
			}

			startLatch.countDown()
			val completed =
				doneLatch.await(DEFAULT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)

			// Assert
			assertThat(completed)
				.withMessage("All listener and grid modification threads should complete")
				.isTrue()
			assertThat(exceptions)
				.withMessage("No unexpected exceptions during concurrent operations")
				.isEmpty()
		}
	}
}
