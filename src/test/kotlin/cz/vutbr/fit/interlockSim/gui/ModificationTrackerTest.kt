/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for ModificationTracker
	Phase 4: GUI and Simulation Tests - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.ContextChangeListener
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.beans.PropertyChangeEvent
import java.io.File

/**
 * Tests for ModificationTracker class.
 *
 * Tests modification state tracking, property change event handling,
 * callback invocation, and file path management.
 */
@DisplayName("ModificationTracker")
class ModificationTrackerTest {
	private lateinit var tracker: ModificationTracker
	private var callbackInvoked: Boolean = false
	private var lastCallbackValue: Boolean? = null

	@BeforeEach
	fun setUp() {
		callbackInvoked = false
		lastCallbackValue = null
		tracker = ModificationTracker { isDirty ->
			callbackInvoked = true
			lastCallbackValue = isDirty
		}
	}

	@Test
	@DisplayName("initial state is clean")
	fun initialStateIsClean() {
		assertThat(tracker.isDirty()).isFalse()
	}

	@Test
	@DisplayName("initial file is null")
	fun initialFileIsNull() {
		assertThat(tracker.getCurrentFile()).isNull()
	}

	@Test
	@DisplayName("markDirty sets dirty flag")
	fun markDirtySetsFlag() {
		tracker.markDirty()
		assertThat(tracker.isDirty()).isTrue()
	}

	@Test
	@DisplayName("markDirty invokes callback with true")
	fun markDirtyInvokesCallback() {
		tracker.markDirty()
		assertThat(callbackInvoked).isTrue()
		assertThat(lastCallbackValue).isEqualTo(true)
	}

	@Test
	@DisplayName("markDirty multiple times invokes callback only once")
	fun markDirtyMultipleTimesInvokesCallbackOnce() {
		tracker.markDirty()
		callbackInvoked = false
		tracker.markDirty()
		assertThat(callbackInvoked).isFalse()
	}

	@Test
	@DisplayName("markClean clears dirty flag")
	fun markCleanClearsFlag() {
		tracker.markDirty()
		tracker.markClean()
		assertThat(tracker.isDirty()).isFalse()
	}

	@Test
	@DisplayName("markClean invokes callback with false")
	fun markCleanInvokesCallback() {
		tracker.markDirty()
		callbackInvoked = false
		tracker.markClean()
		assertThat(callbackInvoked).isTrue()
		assertThat(lastCallbackValue).isEqualTo(false)
	}

	@Test
	@DisplayName("markClean when already clean does not invoke callback")
	fun markCleanWhenCleanDoesNotInvokeCallback() {
		tracker.markClean()
		assertThat(callbackInvoked).isFalse()
	}

	@Test
	@DisplayName("setCurrentFile updates file path")
	fun setCurrentFileUpdatesPath() {
		val file = File("/test/path/file.xml")
		tracker.setCurrentFile(file)
		assertThat(tracker.getCurrentFile()).isEqualTo(file)
	}

	@Test
	@DisplayName("setCurrentFile invokes callback")
	fun setCurrentFileInvokesCallback() {
		val file = File("/test/path/file.xml")
		tracker.setCurrentFile(file)
		assertThat(callbackInvoked).isTrue()
	}

	@Test
	@DisplayName("setCurrentFile to null clears file path")
	fun setCurrentFileToNullClearsPath() {
		val file = File("/test/path/file.xml")
		tracker.setCurrentFile(file)
		tracker.setCurrentFile(null)
		assertThat(tracker.getCurrentFile()).isNull()
	}

	@Test
	@DisplayName("reset clears dirty flag and file path")
	fun resetClearsState() {
		tracker.markDirty()
		tracker.setCurrentFile(File("/test/path/file.xml"))
		tracker.reset()
		assertThat(tracker.isDirty()).isFalse()
		assertThat(tracker.getCurrentFile()).isNull()
	}

	@Test
	@DisplayName("reset invokes callback")
	fun resetInvokesCallback() {
		tracker.markDirty()
		callbackInvoked = false
		tracker.reset()
		assertThat(callbackInvoked).isTrue()
	}

	@Test
	@DisplayName("propertyChange with CELL_ADDED marks dirty")
	fun propertyChangeWithCellAddedMarksDirty() {
		val event = PropertyChangeEvent(this, ContextChangeListener.CELL_ADDED, null, "cell")
		tracker.propertyChange(event)
		assertThat(tracker.isDirty()).isTrue()
	}

	@Test
	@DisplayName("propertyChange with CELL_REMOVED marks dirty")
	fun propertyChangeWithCellRemovedMarksDirty() {
		val event = PropertyChangeEvent(this, ContextChangeListener.CELL_REMOVED, "cell", null)
		tracker.propertyChange(event)
		assertThat(tracker.isDirty()).isTrue()
	}

	@Test
	@DisplayName("propertyChange with JOIN_CREATED marks dirty")
	fun propertyChangeWithJoinCreatedMarksDirty() {
		val event = PropertyChangeEvent(this, ContextChangeListener.JOIN_CREATED, null, "join")
		tracker.propertyChange(event)
		assertThat(tracker.isDirty()).isTrue()
	}

	@Test
	@DisplayName("propertyChange with TRACK_BLOCK_REMOVED marks dirty")
	fun propertyChangeWithTrackBlockRemovedMarksDirty() {
		val event = PropertyChangeEvent(this, ContextChangeListener.TRACK_BLOCK_REMOVED, "block", null)
		tracker.propertyChange(event)
		assertThat(tracker.isDirty()).isTrue()
	}

	@Test
	@DisplayName("propertyChange with JOIN_FAILED does not mark dirty")
	fun propertyChangeWithJoinFailedDoesNotMarkDirty() {
		val event = PropertyChangeEvent(this, ContextChangeListener.JOIN_FAILED, null, "error")
		tracker.propertyChange(event)
		assertThat(tracker.isDirty()).isFalse()
	}

	@Test
	@DisplayName("propertyChange with CONTEXT_CHANGED does not mark dirty")
	fun propertyChangeWithContextChangedDoesNotMarkDirty() {
		val event = PropertyChangeEvent(this, ContextChangeListener.CONTEXT_CHANGED, null, null)
		tracker.propertyChange(event)
		assertThat(tracker.isDirty()).isFalse()
	}

	@Test
	@DisplayName("getTitleSuffix returns empty string when no file")
	fun getTitleSuffixReturnsEmptyWhenNoFile() {
		assertThat(tracker.getTitleSuffix()).isEqualTo("")
	}

	@Test
	@DisplayName("getTitleSuffix returns empty string when clean")
	fun getTitleSuffixReturnsEmptyWhenClean() {
		tracker.setCurrentFile(File("/test/path/file.xml"))
		assertThat(tracker.getTitleSuffix()).isEqualTo("")
	}

	@Test
	@DisplayName("getTitleSuffix returns asterisk when dirty")
	fun getTitleSuffixReturnsAsteriskWhenDirty() {
		tracker.setCurrentFile(File("/test/path/file.xml"))
		tracker.markDirty()
		assertThat(tracker.getTitleSuffix()).isEqualTo("*")
	}

	@Test
	@DisplayName("getDisplayFileName returns null when no file")
	fun getDisplayFileNameReturnsNullWhenNoFile() {
		assertThat(tracker.getDisplayFileName()).isNull()
	}

	@Test
	@DisplayName("getDisplayFileName returns file name when file is set")
	fun getDisplayFileNameReturnsFileName() {
		tracker.setCurrentFile(File("/test/path/myfile.xml"))
		assertThat(tracker.getDisplayFileName()).isEqualTo("myfile.xml")
	}

	@Test
	@DisplayName("tracker works without callback")
	fun trackerWorksWithoutCallback() {
		val trackerNoCallback = ModificationTracker()
		trackerNoCallback.markDirty()
		assertThat(trackerNoCallback.isDirty()).isTrue()
		trackerNoCallback.markClean()
		assertThat(trackerNoCallback.isDirty()).isFalse()
	}
}
