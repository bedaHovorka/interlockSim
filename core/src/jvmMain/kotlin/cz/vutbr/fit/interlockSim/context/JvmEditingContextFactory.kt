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
 * JVM-capable extension of [EditingContextFactory].
 *
 * Adds JVM-only operations on top of the portable [EditingContextFactory]:
 * - File/stream I/O (inherited from [ContextFactory])
 * - Reflection-based object creation ([createNew])
 *
 * JVM implementations ([XMLContextFactory], test doubles) implement this
 * interface. Koin registers both [JvmEditingContextFactory] and the
 * supertype [EditingContextFactory] so that:
 * - commonTest code can inject [EditingContextFactory] (portable mock)
 * - JVM GUI/simulation code can inject [JvmEditingContextFactory] (full API)
 */
interface JvmEditingContextFactory : EditingContextFactory, ContextFactory {
	/**
	 * Create a new railway object in the given context using reflection.
	 *
	 * @param context the editing context to create the object in
	 * @param clazz the class to instantiate (looked up via reflection)
	 * @param arguments constructor arguments
	 * @return the newly created object
	 * @throws Exception if construction fails
	 */
	@Throws(Exception::class)
	fun createNew(
		context: EditingContext,
		clazz: Class<*>,
		vararg arguments: Any
	): Any
}
