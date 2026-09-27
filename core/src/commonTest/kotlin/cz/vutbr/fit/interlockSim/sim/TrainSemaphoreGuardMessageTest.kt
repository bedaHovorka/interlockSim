package cz.vutbr.fit.interlockSim.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the four `semaphoreAction` "logic error" guard message builders on [Train]
 * (Issue #1006).
 *
 * Before this issue, [Train.formatResumePathNullMessage] and
 * [Train.formatPreExistingPathNullMessage] produced byte-identical text even though they guard
 * different variables in different branches — the resume-from-stop branch and the
 * starting-from-near-zero-velocity branch. These tests pin each message to distinguishing text
 * and assert the four are pairwise distinct, so a regression back to a shared literal fails here
 * rather than only in a diagnostic nobody reads.
 */
class TrainSemaphoreGuardMessageTest {
	private val allFourMessages: List<String>
		get() =
			listOf(
				Train.formatNoTopologicalPathWhileResumingMessage(1, "S1"),
				Train.formatOwnershipConflictWhileResumingMessage(1, "S1"),
				Train.formatResumePathNullMessage(1, "S1"),
				Train.formatPreExistingPathNullMessage(1, "S1")
			)

	@Test
	fun noTopologicalPathMessageNamesItsOwnGuard() {
		val msg = Train.formatNoTopologicalPathWhileResumingMessage(7, "S3")
		assertTrue(msg.contains("Train 7"), "names the train: $msg")
		assertTrue(msg.contains("semaphore S3"), "names the semaphore: $msg")
		assertTrue(msg.contains("NoTopologicalPath"), "names its own PathResult branch: $msg")
		assertTrue(msg.contains("while resuming"), "names the branch it fired in: $msg")
	}

	@Test
	fun ownershipConflictMessageNamesItsOwnGuard() {
		val msg = Train.formatOwnershipConflictWhileResumingMessage(7, "S3")
		assertTrue(msg.contains("Train 7"), "names the train: $msg")
		assertTrue(msg.contains("semaphore S3"), "names the semaphore: $msg")
		assertTrue(msg.contains("OwnershipConflict"), "names its own PathResult branch: $msg")
		assertTrue(
			msg.contains("not (fully) reserved for this train"),
			"stays accurate for every OwnershipConflict cause -- another owner, partial " +
				"ownership, or no PathInfo registered, not only a conflicting train: $msg"
		)
	}

	@Test
	fun resumePathNullMessageNamesItsOwnVariable() {
		val msg = Train.formatResumePathNullMessage(7, "S3")
		assertTrue(msg.contains("Train 7"), "names the train: $msg")
		assertTrue(msg.contains("semaphore S3"), "names the semaphore: $msg")
		assertTrue(msg.contains("resumePath"), "names its own guarded variable: $msg")
		assertTrue(msg.contains("findReservedPathForTrain"), "names the re-fetch call: $msg")
	}

	@Test
	fun preExistingPathNullMessageNamesItsOwnVariable() {
		val msg = Train.formatPreExistingPathNullMessage(7, "S3")
		assertTrue(msg.contains("Train 7"), "names the train: $msg")
		assertTrue(msg.contains("semaphore S3"), "names the semaphore: $msg")
		assertTrue(msg.contains("pre-existing path"), "names its own guarded variable: $msg")
		assertTrue(msg.contains("near-zero velocity"), "names the branch it fired in: $msg")
	}

	@Test
	fun allFourGuardMessagesArePairwiseDistinct() {
		val messages = allFourMessages
		assertEquals(
			messages.size,
			messages.toSet().size,
			"all four guard messages must be pairwise distinct, was: $messages"
		)
	}
}
