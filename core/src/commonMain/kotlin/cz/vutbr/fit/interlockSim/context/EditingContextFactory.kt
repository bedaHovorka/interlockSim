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

/**
 * Platform-portable factory for editing context.
 *
 * Declares only the methods that are safe to use on all KMP targets
 * (JVM, linuxX64). JVM-only methods (file/stream I/O, reflection-based
 * object creation) are declared in [JvmEditingContextFactory].
 *
 * This interface is visible in commonMain and commonTest, enabling
 * cross-platform test code to inject a mock/fake implementation via Koin.
 *
 * @see JvmEditingContextFactory (jvmMain) for the full JVM-capable interface
 */
fun interface EditingContextFactory {
	/**
	 * Create an empty editing context.
	 *
	 * @return a new, empty [EditingContext]
	 */
	fun createEmptyContext(): EditingContext
}
