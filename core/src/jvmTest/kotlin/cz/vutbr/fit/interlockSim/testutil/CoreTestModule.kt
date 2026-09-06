package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContextFactory
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.GridTransformer
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * EditingContextFactory for core tests — delegates to XMLContextFactory directly.
 */
private class CoreTestEditingContextFactory : JvmEditingContextFactory {
	private val xmlFactory = XMLContextFactory()

	override fun createEmptyContext(): EditingContext = xmlFactory.createEmptyContext()

	override fun createNew(
		context: EditingContext,
		clazz: Class<*>,
		vararg arguments: Any
	): Any = xmlFactory.createNew(context, clazz, *arguments)

	override fun createContext(file: File): cz.vutbr.fit.interlockSim.context.Context<*, *> =
		xmlFactory.createContext(file)

	override fun createContext(stream: InputStream): cz.vutbr.fit.interlockSim.context.Context<*, *> =
		xmlFactory.createContext(stream)

	override fun saveContext(
		context: cz.vutbr.fit.interlockSim.context.Context<*, *>,
		file: File
	): Boolean = xmlFactory.saveContext(context, file)

	override fun saveContext(
		context: cz.vutbr.fit.interlockSim.context.Context<*, *>,
		stream: OutputStream
	): Boolean = xmlFactory.saveContext(context, stream)
}

/**
 * Core test module - provides all dependencies needed for core module tests.
 *
 * Uses reflection to access XMLContextFactory if available on the classpath.
 * This allows core tests that load XML fixtures to work when the app module is on the test classpath.
 */
val coreTestModule: Module =
	module {
		single { GridTransformer }
		single { ContextTransformer }
		single<JvmEditingContextFactory> { CoreTestEditingContextFactory() }
		single<EditingContextFactory> { get<JvmEditingContextFactory>() }
		single<SimulationContextFactory> {
			DefaultSimulationContextFactory(
				get<JvmEditingContextFactory>(),
				get<SimulationProcessFactory>()
			)
		}

		// Bindings shared with the other test module flavor (scope bindings plus the two
		// shared non-scope bindings) live in sharedSimulationTestScopesModule (Issue #1029).
		includes(sharedSimulationTestScopesModule)
	}
