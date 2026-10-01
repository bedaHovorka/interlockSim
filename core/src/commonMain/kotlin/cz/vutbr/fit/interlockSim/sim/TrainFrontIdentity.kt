/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

/**
 * The discrete identity of a train's front, published as one immutable value so that a reader
 * on another thread sees a consistent set (Issues #1030, #1028).
 *
 * [Train.frontSection], [Train.trainEntrySeparator] and [Train.totalDistance] are computed live
 * from several plain fields of the front site. The simulation thread changes those fields one
 * statement at a time at a block boundary, so a reader on the Swing EDT that reads the getters
 * one by one can combine a section with the entry separator of another section, or the previous
 * blocks of one section with the rebased position of the next. [Train.frontIdentity] holds the
 * last value the simulation thread published after a complete discrete front mutation.
 *
 * The continuous values — the integrated position within the section, the velocity and the
 * acceleration — are deliberately not part of it: kDisco integrates them between discrete
 * events, and its RK stages write trial values into them, so they can only be read live and
 * stale-tolerant. Combine them with an identity through [totalDistance] and [publishedPosition].
 *
 * @property section the section the front is in (or entering) — what [Train.frontSection]
 *   returned at publication; null before the front has entered any section
 * @property entrySeparator the end of [section] through which the front entered it, with the
 *   Issue #788 boundary correction applied — what [Train.trainEntrySeparator] returned at
 *   publication; null before the train has entered the network
 * @property onNext whether the front is entering the upcoming section ([section] is the next
 *   section); false while it is inside [section] or stands at its far end with nothing reserved
 *   beyond it (the Issue #788 boundary state)
 * @property previousBlocksLength the total length of the sections the front has fully traversed
 * @since Issues #1030, #1028
 */
data class TrainFrontIdentity(
	val section: TrackSection?,
	val entrySeparator: DynamicPathSeparator?,
	val onNext: Boolean,
	val previousBlocksLength: Double
) {
	/**
	 * Total distance the front has covered, from this identity and a live read of
	 * [Train.frontIntegratedPosition]; the off-thread counterpart of [Train.totalDistance].
	 */
	fun totalDistance(frontIntegratedPosition: Double): Double = previousBlocksLength + frontIntegratedPosition

	/**
	 * Distance along [section] to publish, from this identity and a live read of
	 * [Train.frontIntegratedPosition]; the off-thread counterpart of [Train.frontPosition].
	 *
	 * In the Issue #788 boundary state (not [onNext], with a [section]) the front stands at the far
	 * end of [section], so the section length is published; otherwise the integrated position.
	 */
	fun publishedPosition(frontIntegratedPosition: Double): Double =
		if (!onNext && section != null) section.length() else frontIntegratedPosition

	companion object {
		/** The identity of a train whose front has not entered the network yet. */
		val NOT_ENTERED: TrainFrontIdentity =
			TrainFrontIdentity(section = null, entrySeparator = null, onNext = false, previousBlocksLength = 0.0)
	}
}
