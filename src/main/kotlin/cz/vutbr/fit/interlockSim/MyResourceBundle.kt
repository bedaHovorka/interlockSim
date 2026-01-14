/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import java.io.File
import java.io.InputStream
import java.util.ListResourceBundle

private val BASE = "resource" + File.separatorChar

/**
 * Resources
 */
class MyResourceBundle : ListResourceBundle() {

	override fun getContents(): Array<Array<Any>>? {
		// Returns null as resources are loaded via getFile() and getSchema() methods instead
		return null
	}

	/**
	 * @return schema file for validation
	 */
	fun getSchema(): InputStream? = getFile("data.xsd")

	/**
	 * @param name
	 * @return file from resource
	 */
	fun getFile(name: String): InputStream? = javaClass.getResourceAsStream(BASE + name)
}
