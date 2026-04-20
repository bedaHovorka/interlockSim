/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for the `--debug` flag, [configureLogging] function, and [filterPositionalArgs] helper.
 *
 * Verifies that enabling debug mode sets the log level to [Level.DEBUG] and installs the
 * stderr appender, that disabling it restores [Level.OFF] and the default appender, and
 * that [filterPositionalArgs] correctly strips flag arguments from positional arg lists.
 *
 * @since Issue #449 (add --debug flag for runtime log output)
 */
class DebugFlagTest {

	private var savedLevel: Level = Level.OFF
	private lateinit var savedAppender: Appender

	@BeforeTest
	fun saveLoggingConfig() {
		savedLevel = KotlinLoggingConfiguration.logLevel
		savedAppender = KotlinLoggingConfiguration.appender
	}

	@AfterTest
	fun restoreLoggingConfig() {
		KotlinLoggingConfiguration.logLevel = savedLevel
		KotlinLoggingConfiguration.appender = savedAppender
	}

	// ---- CMD_DEBUG constant ----

	@Test
	fun `CMD_DEBUG constant has expected value`() {
		assertThat(CMD_DEBUG).isEqualTo("--debug")
	}

	// ---- configureLogging ----

	@Test
	fun `configureLogging with debug false sets log level to OFF`() {
		configureLogging(false)
		assertThat(KotlinLoggingConfiguration.logLevel).isEqualTo(Level.OFF)
	}

	@Test
	fun `configureLogging with debug false restores default appender`() {
		configureLogging(false)
		assertThat(KotlinLoggingConfiguration.appender).isSameInstanceAs(DEFAULT_APPENDER)
	}

	@Test
	fun `configureLogging with debug true sets log level to DEBUG`() {
		configureLogging(true)
		assertThat(KotlinLoggingConfiguration.logLevel).isEqualTo(Level.DEBUG)
	}

	@Test
	fun `configureLogging with debug true then false restores OFF level`() {
		configureLogging(true)
		configureLogging(false)
		assertThat(KotlinLoggingConfiguration.logLevel).isEqualTo(Level.OFF)
	}

	@Test
	fun `configureLogging with debug true then false restores default appender`() {
		configureLogging(true)
		configureLogging(false)
		assertThat(KotlinLoggingConfiguration.appender).isSameInstanceAs(DEFAULT_APPENDER)
	}

	// ---- filterPositionalArgs ----

	@Test
	fun `filterPositionalArgs removes --debug from args`() {
		val result = filterPositionalArgs(arrayOf("--debug", "example", "shuntingLoop", "60"))
		assertThat(result.toList()).containsExactly("example", "shuntingLoop", "60")
	}

	@Test
	fun `filterPositionalArgs removes --verbose from args`() {
		val result = filterPositionalArgs(arrayOf("--verbose", "sim", "file.xml", "300"))
		assertThat(result.toList()).containsExactly("sim", "file.xml", "300")
	}

	@Test
	fun `filterPositionalArgs removes --quiet from args`() {
		val result = filterPositionalArgs(arrayOf("--quiet", "example", "shuntingLoop", "60"))
		assertThat(result.toList()).containsExactly("example", "shuntingLoop", "60")
	}

	@Test
	fun `filterPositionalArgs removes --debug and --verbose together`() {
		val result = filterPositionalArgs(arrayOf("--debug", "--verbose", "sim", "net.xml", "120"))
		assertThat(result.toList()).containsExactly("sim", "net.xml", "120")
	}

	@Test
	fun `filterPositionalArgs handles --debug after positional args`() {
		val result = filterPositionalArgs(arrayOf("example", "shuntingLoop", "60", "--debug"))
		assertThat(result.toList()).containsExactly("example", "shuntingLoop", "60")
	}

	@Test
	fun `filterPositionalArgs returns empty array when only flags are given`() {
		val result = filterPositionalArgs(arrayOf("--debug", "--verbose", "--quiet"))
		assertThat(result.toList()).containsExactly()
	}

	@Test
	fun `filterPositionalArgs returns args unchanged when no flags are present`() {
		val result = filterPositionalArgs(arrayOf("example", "shuntingLoop", "60"))
		assertThat(result.toList()).containsExactly("example", "shuntingLoop", "60")
	}
}
