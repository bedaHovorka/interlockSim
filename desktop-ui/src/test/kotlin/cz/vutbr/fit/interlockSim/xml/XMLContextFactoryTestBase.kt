/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 3: XML Parsing & Configuration Tests
 */
package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Resources
import org.koin.test.inject
import java.io.InputStream

/**
 * Shared scaffolding for the [XMLContextFactory] test classes (PR #1043 review round):
 * the Koin-injected factory and the fixture-stream loader for the fixtures in
 * src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/.
 *
 * Abstract on purpose: JUnit never discovers it as a test class. Concrete classes
 * declare their own @Timeout so the 10 s limit is visible in each file (@Timeout is
 * @Inherited and would propagate anyway).
 */
abstract class XMLContextFactoryTestBase : KoinTestBase() {
	protected val editingContextFactory: XMLContextFactory by inject()

	// Helper method to load fixture files from resources
	protected fun getFixtureStream(fileName: String): InputStream {
		val resourcePath = "/cz/vutbr/fit/interlockSim/xml/fixtures/$fileName"
		val stream = Resources.read(resourcePath.trimStart('/')).byteInputStream()
		assertThat(stream)
			.withMessage("Fixture file should exist: $fileName")
			.isNotNull()
		return stream
	}
}
