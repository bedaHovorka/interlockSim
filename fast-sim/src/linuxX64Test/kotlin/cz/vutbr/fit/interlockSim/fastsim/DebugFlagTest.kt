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
import assertk.assertions.isEqualTo
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for the `--debug` flag and [configureLogging] function.
 *
 * Verifies that enabling debug mode sets the log level to [Level.DEBUG]
 * and that disabling it restores [Level.OFF].
 *
 * @since Issue #449 (add --debug flag for runtime log output)
 */
class DebugFlagTest {

	private var savedLevel: Level = Level.OFF

	@BeforeTest
	fun saveLogLevel() {
		savedLevel = KotlinLoggingConfiguration.logLevel
	}

	@AfterTest
	fun restoreLogLevel() {
		KotlinLoggingConfiguration.logLevel = savedLevel
	}

	@Test
	fun `CMD_DEBUG constant has expected value`() {
		assertThat(CMD_DEBUG).isEqualTo("--debug")
	}

	@Test
	fun `configureLogging with debug false sets log level to OFF`() {
		configureLogging(false)
		assertThat(KotlinLoggingConfiguration.logLevel).isEqualTo(Level.OFF)
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
}
