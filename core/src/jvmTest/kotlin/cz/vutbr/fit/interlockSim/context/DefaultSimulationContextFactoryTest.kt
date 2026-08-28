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
		TestFixtures.loadShuntingXml().use { input ->
			shuntingFile.outputStream().use { output ->
				input.copyTo(output)
			}
		}
	}

	@Nested
	@DisplayName("createContext from file")
	inner class CreateContextFromFileTests {
		@Test
		fun createContext_file_returnsSimulationContext() {
			factory.createContext(shuntingFile).use { result ->
				assertThat(result).isNotNull()
				assertThat(result).isInstanceOf<SimulationContext>()
			}
		}
	}

	@Nested
	@DisplayName("createContext from stream")
	inner class CreateContextFromStreamTests {
		@Test
		fun createContext_inputStream_returnsSimulationContext() {
			TestFixtures.loadShuntingXml().use { stream ->
				factory.createContext(stream).use { result ->
					assertThat(result).isNotNull()
					assertThat(result).isInstanceOf<SimulationContext>()
				}
			}
		}
	}

	@Nested
	@DisplayName("createContext from EditingContext")
	inner class CreateContextFromEditingContextTests {
		@Test
		fun createContext_editingContext_returnsSimulationContext() {
			TestFixtures.loadShuntingEditingContext(editingFactory).use { editingContext ->
				factory.createContext(editingContext).use { result ->
					assertThat(result).isNotNull()
					assertThat(result).isInstanceOf<SimulationContext>()
				}
			}
		}

		@Test
		fun createContext_editingContext_resultIsFrozen() {
			TestFixtures.loadShuntingEditingContext(editingFactory).use { editingContext ->
				factory.createContext(editingContext).use { result ->
					assertThat((result as BaseContext<*>).isFrozen()).isTrue()
				}
			}
		}
	}

	@Nested
	@DisplayName("saveContext")
	inner class SaveContextTests {
		@Test
		fun saveContext_toFile_returnsTrueAndWritesFile() {
			TestFixtures.loadShuntingEditingContext(editingFactory).use { editingContext ->
				val outFile = tmpDir.resolve("saved.xml").toFile()
				val result = factory.saveContext(editingContext, outFile)
				assertThat(result).isTrue()
				assertThat(outFile.exists()).isTrue()
			}
		}

		@Test
		fun saveContext_toStream_returnsTrue() {
			TestFixtures.loadShuntingEditingContext(editingFactory).use { editingContext ->
				val result =
					tmpDir
						.resolve("saved-stream.xml")
						.toFile()
						.outputStream()
						.use { factory.saveContext(editingContext, it) }
				assertThat(result).isTrue()
			}
		}
	}

	@Nested
	@DisplayName("createEmptyContext")
	inner class CreateEmptyContextTests {
		@Test
		fun createEmptyContext_returnsSimulationContext() {
			factory.createEmptyContext().use { result ->
				assertThat(result).isNotNull()
				assertThat(result).isInstanceOf<SimulationContext>()
			}
		}

		@Test
		fun createEmptyContext_hasNoInOuts() {
			factory.createEmptyContext().use { result ->
				assertThat(result.getInOuts().isEmpty()).isTrue()
			}
		}
	}
}
