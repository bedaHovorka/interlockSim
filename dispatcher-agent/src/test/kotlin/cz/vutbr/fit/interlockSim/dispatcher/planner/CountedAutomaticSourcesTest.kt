/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [countedAutomaticsFrom] reads each counted automatic without an action path from its source (Issue
 * #1476): the kernel's expiry acts from the run record (rows C1-C3, C3 apart from C1), the sweeper's acts
 * from the sweeper (C4-C6).
 */
@DisplayName("The counted automatics are read by row from the run record and the sweeper (Issue #1476)")
class CountedAutomaticSourcesTest {
	private val record =
		RunRecord(
			journeysCompleted = 0,
			trainsEntered = 0,
			trainsExited = 0,
			wrongExits = 0,
			maxConcurrentTrains = 0,
			blockTransitions = 0,
			conflicts = 0,
			failedReservations = 0,
			failedReservationsByKind = emptyMap(),
			unresolvedAtRunEnd = 0,
			entryFailures = 0,
			entryFailuresByKind = emptyMap(),
			stall = null,
			expiredHoldingCount = 0,
			expiredTailCount = 0,
			staleReleaseCount = 9,
			partialReleaseCount = 9,
			capacityShownToDispatcher = null,
			topologyCapacity = null,
			unfinishedJourneys = 0,
			endTime = 600.0,
			endTimeReached = true,
			classification = RunEndClassification.NATURAL_COMPLETION,
			wholeHoldingExpiryActs = 1,
			tailExpiryActs = 2,
			requestExpiryActs = 3
		)

	private val sweeper =
		mockk<OrphanReservationSweeper> {
			every { staleActCount } returns 4
			every { partialActCount } returns 5
			every { phantomActCount } returns 6
		}

	@Test
	fun `each row comes from its own source`() {
		assertThat(countedAutomaticsFrom(record, sweeper)).isEqualTo(
			mapOf(
				CountedAutomatic.EXPIRY_WHOLE_HOLDING to 1L,
				CountedAutomatic.EXPIRY_OCCUPYING_TAIL to 2L,
				CountedAutomatic.EXPIRY_ON_REQUEST to 3L,
				// The sweeper's act counts, not the record's release counts (which count deferred retries).
				CountedAutomatic.SWEEP_STALE to 4L,
				CountedAutomatic.SWEEP_PARTIAL to 5L,
				CountedAutomatic.SWEEP_PHANTOM to 6L
			)
		)
	}

	@Test
	fun `a run without a record or a sweeper contributes nothing`() {
		assertThat(countedAutomaticsFrom(null, null)).isEmpty()
	}
}
