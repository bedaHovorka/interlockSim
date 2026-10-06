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
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Tests for the shared [extractUniqueBlocks] helper (Issue #1123), used by both
 * [DefaultPathReservationService] and `MultiTrainLoop`.
 */
class UniqueBlocksTest {
	private lateinit var appender: ListAppender<ILoggingEvent>

	private val end1 = mockk<PathSeparator>()
	private val end2 = mockk<PathSeparator>()
	private val dynEnd1 = mockk<DynamicPathSeparator>()
	private val dynEnd2 = mockk<DynamicPathSeparator>()

	private val block1 = DynamicTrackBlock(SimpleTrackBlock(end1, end2, 100.0, 30.0, 30.0), dynEnd1, dynEnd2)
	private val block2 = DynamicTrackBlock(SimpleTrackBlock(end1, end2, 200.0, 40.0, 40.0), dynEnd1, dynEnd2)

	@BeforeEach
	fun attachAppender() {
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(appender)
	}

	@AfterEach
	fun detachAppender() {
		(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(appender)
	}

	private fun mismatchWarnings() =
		appender.list.filter {
			it.level == Level.WARN && "extractUniqueBlocks: Unexpected non-DynamicTrackBlock" in it.formattedMessage
		}

	@Test
	fun `keeps path order and drops repeated blocks silently`() {
		assertThat(extractUniqueBlocks(listOf(block1, block2, block1, block2))).containsExactly(block1, block2)
		assertThat(mismatchWarnings()).isEmpty()
	}

	@Test
	fun `empty path yields no blocks`() {
		assertThat(extractUniqueBlocks(emptyList())).isEmpty()
	}

	@Test
	fun `drops a non-dynamic block and logs the context type mismatch at WARN`() {
		val staticSection = SimpleTrackBlock(end1, end2, 50.0, 20.0, 20.0).apply { name = "kStatic" }

		assertThat(extractUniqueBlocks(listOf(block1, staticSection, block2))).containsExactly(block1, block2)
		val warnings = mismatchWarnings()
		assertThat(warnings).hasSize(1)
		assertThat(warnings.single().formattedMessage).contains("SimpleTrackBlock from section kStatic")
	}
}
