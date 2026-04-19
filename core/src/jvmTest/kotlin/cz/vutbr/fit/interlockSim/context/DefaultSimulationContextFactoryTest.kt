/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.test.inject
import java.io.File
import java.nio.file.Path

/**
 * Tests for [DefaultSimulationContextFactory] — verifies all delegation paths:
 * createContext(File), createContext(InputStream), createContext(EditingContext),
 * saveContext, and createEmptyContext.
 */
@DisplayName("DefaultSimulationContextFactory")
class DefaultSimulationContextFactoryTest : KoinTestBase() {

	private val factory: SimulationContextFactory by inject()
	private val editingFactory: JvmEditingContextFactory by inject()

	@TempDir
	lateinit var tmpDir: Path

	private lateinit var shuntingFile: File

	@BeforeEach
	fun writeTempXml() {
		shuntingFile = tmpDir.resolve("vyhybna.xml").toFile()
		shuntingFile.outputStream().use { TestFixtures.loadShuntingXml().copyTo(it) }
	}

	@Nested
	@DisplayName("createContext from file")
	inner class CreateContextFromFileTests {

		@Test
		fun createContext_file_returnsSimulationContext() {
			val result = factory.createContext(shuntingFile)
			assertThat(result).isNotNull()
			assertThat(result).isInstanceOf<SimulationContext>()
		}
	}

	@Nested
	@DisplayName("createContext from stream")
	inner class CreateContextFromStreamTests {

		@Test
		fun createContext_inputStream_returnsSimulationContext() {
			val result = factory.createContext(TestFixtures.loadShuntingXml())
			assertThat(result).isNotNull()
			assertThat(result).isInstanceOf<SimulationContext>()
		}
	}

	@Nested
	@DisplayName("createContext from EditingContext")
	inner class CreateContextFromEditingContextTests {

		@Test
		fun createContext_editingContext_returnsSimulationContext() {
			val editingContext = editingFactory.createContext(TestFixtures.loadShuntingXml()) as EditingContext
			val result = factory.createContext(editingContext)
			assertThat(result).isNotNull()
			assertThat(result).isInstanceOf<SimulationContext>()
		}

		@Test
		fun createContext_editingContext_resultIsFrozen() {
			val editingContext = editingFactory.createContext(TestFixtures.loadShuntingXml()) as EditingContext
			val result = factory.createContext(editingContext) as BaseContext<*>
			assertThat(result.isFrozen()).isTrue()
		}
	}

	@Nested
	@DisplayName("saveContext")
	inner class SaveContextTests {

		@Test
		fun saveContext_toFile_returnsTrueAndWritesFile() {
			// saveContext delegates to editingFactory which requires EditingContext
			val editingCtx = editingFactory.createContext(TestFixtures.loadShuntingXml()) as EditingContext
			val outFile = tmpDir.resolve("saved.xml").toFile()
			val result = factory.saveContext(editingCtx, outFile)
			assertThat(result).isTrue()
			assertThat(outFile.exists()).isTrue()
		}

		@Test
		fun saveContext_toStream_returnsTrue() {
			val editingCtx = editingFactory.createContext(TestFixtures.loadShuntingXml()) as EditingContext
			val outStream = tmpDir.resolve("saved-stream.xml").toFile().outputStream()
			val result = outStream.use { factory.saveContext(editingCtx, it) }
			assertThat(result).isTrue()
		}
	}

	@Nested
	@DisplayName("createEmptyContext")
	inner class CreateEmptyContextTests {

		@Test
		fun createEmptyContext_returnsSimulationContext() {
			val result = factory.createEmptyContext()
			assertThat(result).isNotNull()
			assertThat(result).isInstanceOf<SimulationContext>()
		}

		@Test
		fun createEmptyContext_hasNoInOuts() {
			val result = factory.createEmptyContext()
			assertThat(result.getInOuts().isEmpty()).isTrue()
		}
	}
}
