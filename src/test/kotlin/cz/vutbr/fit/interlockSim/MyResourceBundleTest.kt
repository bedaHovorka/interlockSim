package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.Test
import org.koin.test.inject

class MyResourceBundleTest : KoinTestBase() {
	private val myResourceBundle: MyResourceBundle by inject()

	@Test
	fun testGetSchema() {
		myResourceBundle.getSchema().use {
			assertThat(it, "Schema InputStream should not be null").isNotNull()
		}
	}

	@Test
	fun testGetFile() {
		val testFileName = "data.xsd"
		myResourceBundle.getFile(testFileName).use {
			assertThat(it, "File InputStream for $testFileName should not be null").isNotNull()
		}
	}
}
