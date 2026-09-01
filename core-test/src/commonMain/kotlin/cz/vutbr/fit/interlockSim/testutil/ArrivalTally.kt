/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: count completed journeys from TRAIN_EVENTS report messages
 */
package cz.vutbr.fit.interlockSim.testutil

/**
 * Counts completed journeys from their `ReportType.TRAIN_EVENTS` report messages, so a
 * boundary-state regression test can prove it was not vacuous.
 *
 * `Train.actions()` reports a completed journey as a message such as `12.0 Train #3 ends`.
 * Three tests spelled this suffix match plus a `Train #(\d+)` extraction out privately
 * (desktop `RedSignalWaitHeadingFlipRegressionTest`, the #788 core and boundary tests);
 * this is that tally (extracted with the #1015 dedup pass).
 *
 * `count` and [arrivedTrainNumbers] are deliberately both exposed: `count` is the number
 * of arrival messages, [arrivedTrainNumbers] the distinct trains behind them. They differ
 * only if a train number completes more than one journey, which no current scenario does.
 *
 * ```kotlin
 * val arrivals = ArrivalTally()
 * runSampled(context) { message -> message?.let(arrivals::record) }
 * assertThat(arrivals.arrivedTrainNumbers.size >= 3).isTrue()
 * ```
 */
class ArrivalTally {
	private companion object {
		/** `Train.actions()` reports the completed journey as a TRAIN_EVENTS message ending here. */
		const val ARRIVAL_SUFFIX: String = " ends"

		/** Extracts the train number from a report message such as `12.0 Train #3 ends`. */
		val TRAIN_NUMBER_REGEX = Regex("""Train #(\d+)""")
	}

	private val numbers = mutableSetOf<Int>()

	/** Number of arrival messages recorded so far. */
	var count: Int = 0
		private set

	/** Train numbers that completed at least one journey. */
	val arrivedTrainNumbers: Set<Int>
		get() = numbers

	/** Records [message] if it is a completed-journey report; every other message is ignored. */
	fun record(message: String) {
		if (!message.endsWith(ARRIVAL_SUFFIX)) return
		val number = TRAIN_NUMBER_REGEX.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return
		numbers.add(number)
		count++
	}
}
