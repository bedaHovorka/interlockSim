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
 * Factory for editing context
 */
interface EditingContextFactory : ContextFactory {
	/**
	 * create new context
	 * @return empty context
	 */
	fun createEmptyContext(): EditingContext

	/**
	 * @param context
	 * @param clazz
	 * @param arguments
	 * @return create new object in context
	 * @throws Exception
	 */
	@Throws(Exception::class)
	fun createNew(
		context: EditingContext,
		clazz: Class<*>,
		vararg arguments: Any
	): Any
}
