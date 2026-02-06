/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Performance Benchmarks
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.di

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.TimeUnit

/**
 * Railway-specific performance benchmarks measuring Koin DI overhead.
 *
 * These benchmarks validate the performance requirements from Issue #219:
 * - Railway network loading from XML: < 10ms overhead with Koin
 * - Simulation initialization: < 5% increase with DI
 * - Train event processing: < 2% throughput decrease
 *
 * Railway Context:
 * In real railway interlocking systems, timing is critical. Train control
 * decisions must be made within strict time windows. These benchmarks ensure
 * our DI framework doesn't introduce unacceptable delays that could affect
 * simulation accuracy.
 *
 * @see KoinGoldenOutputTest - Functional validation of Koin integration
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
open class KoinPerformanceBenchmark {

	/**
	 * Railway network XML stream for benchmarking.
	 * Uses vyhybna.xml (shunting loop configuration) as the standard test network.
	 */
	private fun railwayNetworkXml() = 
		javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			?: throw IllegalStateException("Railway network XML not found")

	/**
	 * Benchmark: Load railway network WITHOUT dependency injection.
	 *
	 * Baseline measurement for direct instantiation of XMLContextFactory.
	 * This represents the pre-Koin performance that we're comparing against.
	 *
	 * Railway Domain: Loading track layouts, switches, and signals from configuration.
	 */
	@Benchmark
	fun railwayNetworkLoading_WithoutDI(blackhole: Blackhole) {
		val xmlFactory = XMLContextFactory()
		val networkStream = railwayNetworkXml()
		val railwayNetwork = xmlFactory.createContext(networkStream)
		networkStream.close()
		
		blackhole.consume(railwayNetwork)
		railwayNetwork.close()
	}

	/**
	 * Benchmark: Load railway network WITH Koin dependency injection.
	 *
	 * Measures overhead introduced by DI container for context factory resolution.
	 * Target: < 10ms overhead compared to baseline.
	 *
	 * Railway Domain: Same network loading but through DI-managed factory.
	 */
	@Benchmark
	fun railwayNetworkLoading_WithKoin(blackhole: Blackhole) {
		startKoin { modules(interlockSimModule) }
		try {
			val factoryFromDI = org.koin.java.KoinJavaComponent.get<SimulationContextFactory>(
				SimulationContextFactory::class.java
			)
			val networkStream = railwayNetworkXml()
			val railwayNetwork = factoryFromDI.createContext(networkStream)
			networkStream.close()
			
			blackhole.consume(railwayNetwork)
			railwayNetwork.close()
		} finally {
			stopKoin()
		}
	}

	/**
	 * Benchmark: Initialize train simulation WITHOUT DI.
	 *
	 * Baseline for simulation setup including ShuntingLoop process creation.
	 * Measures time from context creation to simulation readiness.
	 *
	 * Railway Domain: Setting up train schedules, routes, and initial positions.
	 */
	@Benchmark
	fun trainSimulationSetup_WithoutDI(blackhole: Blackhole) {
		val xmlFactory = XMLContextFactory()
		val networkStream = railwayNetworkXml()
		val railwayNetwork = xmlFactory.createContext(networkStream) as DefaultSimulationContext
		networkStream.close()
		
		val mockContext = MockSimulationContext(railwayNetwork)
		val simulationScenario = ShuntingLoop(mockContext, 60L)
		
		blackhole.consume(simulationScenario)
		railwayNetwork.close()
	}

	/**
	 * Benchmark: Initialize train simulation WITH Koin.
	 *
	 * Measures DI overhead in simulation process creation.
	 * Target: < 5% increase over baseline.
	 *
	 * Railway Domain: Same simulation setup through DI-managed components.
	 */
	@Benchmark
	fun trainSimulationSetup_WithKoin(blackhole: Blackhole) {
		startKoin { modules(interlockSimModule) }
		try {
			val factoryFromDI = org.koin.java.KoinJavaComponent.get<SimulationContextFactory>(
				SimulationContextFactory::class.java
			)
			val networkStream = railwayNetworkXml()
			val railwayNetwork = factoryFromDI.createContext(networkStream) as DefaultSimulationContext
			networkStream.close()
			
			val mockContext = MockSimulationContext(railwayNetwork)
			val simulationScenario = ShuntingLoop(mockContext, 60L)
			
			blackhole.consume(simulationScenario)
			railwayNetwork.close()
		} finally {
			stopKoin()
		}
	}

	/**
	 * Benchmark: Repeated context factory resolution.
	 *
	 * Simulates high-frequency DI lookups (e.g., creating multiple simulation
	 * contexts during batch testing or scenario exploration).
	 *
	 * Railway Domain: Operational analysis requiring many simulation runs
	 * with different configurations.
	 *
	 * Target: Sub-microsecond resolution time after container warmup.
	 */
	@Benchmark
	@BenchmarkMode(Mode.AverageTime)
	@OutputTimeUnit(TimeUnit.MICROSECONDS)
	fun factoryResolution_RepeatedLookups(blackhole: Blackhole, diState: DIContainerState) {
		val factoryInstance = org.koin.java.KoinJavaComponent.get<SimulationContextFactory>(
			SimulationContextFactory::class.java
		)
		blackhole.consume(factoryInstance)
	}

	/**
	 * State holder for pre-initialized DI container.
	 * Isolates container startup cost from resolution benchmarks.
	 */
	@State(Scope.Benchmark)
	open class DIContainerState {
		@Setup(Level.Trial)
		fun initializeDI() {
			stopKoin() // Clean slate
			startKoin { modules(interlockSimModule) }
		}

		@TearDown(Level.Trial)
		fun shutdownDI() {
			stopKoin()
		}
	}
}
