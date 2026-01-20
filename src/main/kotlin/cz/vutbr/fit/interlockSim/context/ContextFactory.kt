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

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Context Factory Interface
 *
 */
interface ContextFactory {
	/**
	 * create context from file
	 * @param file source
	 * @return context
	 * @throws ContextCreationException if source is wrong
	 */
	fun createContext(file: File): Context<*>

	/**
	 * create context from stream
	 * @param stream source
	 * @return context
	 * @throws ContextCreationException if source is wrong
	 */
	fun createContext(stream: InputStream): Context<*>

	/**
	 * save context to file
	 * @param context
	 * @param file
	 * @return if save was success
	 */
	fun saveContext(
		context: Context<*>,
		file: File
	): Boolean

	/**
	 * save context to stream
	 * @param context
	 * @param stream
	 * @return if save was success
	 */
	fun saveContext(
		context: Context<*>,
		stream: OutputStream
	): Boolean
}
