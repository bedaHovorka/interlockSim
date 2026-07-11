package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Tests the SP0.9 [ControlStepListener] seam in [ShuntingLoop.iteration] — the
 * load-bearing placement of `controlStepListener?.onControlStep()` at the top of
 * each iteration, **before** admission decisions are built.
 *
 * The existing `DispatchDecisionApplierTest` (in `:dispatcher-agent`) exercises the
 * applier in isolation by calling `onControlStep()` on the test thread; it does **not**
 * verify that `ShuntingLoop.iteration()` actually invokes the listener on the sim
 * thread, or that it does so before the admission `Dispatcher.decide` call. These
 * tests close that gap by driving a real `context.run()` with a custom [Dispatcher]
 * and a [ControlStepListener], observing the real iteration lifecycle.
 *
 * @since Issue #731 (SP0.9 — Goal 10, PR #737 review follow-up)
 */
@DisplayName("ShuntingLoop ControlStepListener seam (SP0.9, #737)")
@Tag("integration-test")
class ShuntingLoopControlStepListenerTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `controlStepListener is invoked once per iteration`() {
		val context = loadVyhybnaContext()
		// Initialize dynamic wrapper map before creating ShuntingLoop.
		context.getInOuts()

		val listenerCalls = AtomicInteger(0)

		// SP0.11: ShuntingLoop no longer accepts a dispatcher — inline dispatch is removed.
		// With endTime = 0L the simulation performs exactly one iteration, so the listener
		// must fire exactly once.
		val shuntingLoop = ShuntingLoop(context, endTime = 0L)
		shuntingLoop.controlStepListener = ControlStepListener { listenerCalls.incrementAndGet() }
		context.setMainProcess(shuntingLoop)
		context.run()

		logger.info { "listenerCalls=${listenerCalls.get()}" }
		assertThat(listenerCalls.get()).isEqualTo(1)
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `approveQueuedTrain moves a queued train into the approved set`() {
		val context = loadVyhybnaContext()
		context.getInOuts()

		val approvedByCallback = AtomicInteger(0)
		val shuntingLoopRef = AtomicReference<ShuntingLoop?>(null)

		// SP0.11: ShuntingLoop no longer accepts a dispatcher. Train IDs are obtained from
		// ShuntingLoop.getQueuedTrains(), which returns the observation data published at the
		// start of each iteration, before the listener fires.
		//
		// The listener approves the first unapproved train each tick; approveQueuedTrain is
		// idempotent since SP0.11 (silently no-ops if the train is already approved), so there
		// is no risk of a crash from the async driver posting a duplicate ApproveTrain.
		val shuntingLoop = ShuntingLoop(context, endTime = 30L)
		shuntingLoopRef.set(shuntingLoop)
		shuntingLoop.controlStepListener =
			ControlStepListener {
				val loop = shuntingLoopRef.get() ?: return@ControlStepListener
				loop.getQueuedTrains().firstOrNull()?.let { obs ->
					loop.approveQueuedTrain(obs.trainId)
					approvedByCallback.incrementAndGet()
				}
			}
		context.setMainProcess(shuntingLoop)
		context.run()

		val entered = shuntingLoop.getTrainsEntered()
		val maxConcurrent = shuntingLoop.getMaxConcurrentTrains()
		logger.info {
			"entered=$entered, maxConcurrent=$maxConcurrent, approvedByCallback=${approvedByCallback.get()}"
		}

		// A train was queued by the generator...
		assertThat(entered).isGreaterThanOrEqualTo(1)
		// ...and the listener approved at least one via the callback...
		assertThat(approvedByCallback.get()).isGreaterThanOrEqualTo(1)
		// ...and it entered the approved set. Since no dispatcher runs inline, this could
		// only happen via approveQueuedTrain -> moved to approwedTrains.
		assertThat(maxConcurrent).isGreaterThanOrEqualTo(1)
	}
}
