package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
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
import java.util.concurrent.atomic.AtomicBoolean
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

	/**
	 * The listener is invoked exactly once per iteration, and its invocation at the
	 * top of `iteration()` precedes the admission `Dispatcher.decide` call (i.e. it
	 * runs before `buildAdmissionObservation()` is consumed).
	 *
	 * With `endTime = 0L` the simulation performs exactly one iteration, so the
	 * listener must fire exactly once. The custom [Dispatcher] records, inside its
	 * admission `decide()`, whether the listener has already run this tick — proving
	 * the ordering invariant the whole SP0.9 design rests on.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `controlStepListener is invoked once per iteration, before admission`() {
		val context = loadVyhybnaContext()
		// Initialize dynamic wrapper map before creating ShuntingLoop (matches the
		// example/regression test pattern — see ShuntingLoopRegressionTest).
		context.getInOuts()

		val listenerCalls = AtomicInteger(0)
		val listenerRanBeforeAdmission = AtomicBoolean(false)

		// decide() is called at the admission phase right after buildAdmissionObservation(),
		// which is AFTER controlStepListener?.onControlStep() at the top of iteration().
		// So if the listener ran before admission, listenerCalls is already >= 1 here.
		val dispatcher =
			object : Dispatcher {
				override fun decide(observed: DispatchObservation): List<DispatchDecision> {
					if (listenerCalls.get() >= 1) {
						listenerRanBeforeAdmission.set(true)
					}
					return listOf(DispatchDecision.NoAction)
				}
			}

		val shuntingLoop = ShuntingLoop(context, endTime = 0L, dispatcher = dispatcher)
		shuntingLoop.controlStepListener = ControlStepListener { listenerCalls.incrementAndGet() }
		context.setMainProcess(shuntingLoop)
		context.run()

		logger.info { "listenerCalls=${listenerCalls.get()}, ranBeforeAdmission=${listenerRanBeforeAdmission.get()}" }
		assertThat(listenerCalls.get()).isEqualTo(1)
		assertThat(listenerRanBeforeAdmission.get()).isTrue()
	}

	/**
	 * `approveQueuedTrain(trainId)` delegates to the private `applyApproveTrain`,
	 * moving a queued train from the unapproved set into the approved set and
	 * activating it — observable via `getMaxConcurrentTrains()`.
	 *
	 * The custom [Dispatcher] never returns an `ApproveTrain` decision (always
	 * `NoAction`), so the **only** path by which a train can enter the approved set
	 * is the listener calling `approveQueuedTrain`. The dispatcher captures the first
	 * unapproved train id from the admission observation; the listener consumes that
	 * id **once** (`getAndSet(null)`) and approves it on the next tick (the listener
	 * runs before the `decide()` that captures the id, so there is a one-tick lag).
	 * Consuming once is essential: re-approving an already-approved id would throw
	 * inside `applyApproveTrain`'s `requireNotNull` and crash the ShuntingLoop
	 * process mid-iteration. `endTime = 30L` gives enough ticks for the generator to
	 * place a train and the listener to approve it.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `approveQueuedTrain moves a queued train into the approved set`() {
		val context = loadVyhybnaContext()
		context.getInOuts()

		val capturedTrainId = AtomicReference<String?>(null)
		val approvedByCallback = AtomicInteger(0)
		val shuntingLoopRef = AtomicReference<ShuntingLoop?>(null)

		// Always NoAction: no auto-approval, no auto-reservation. The only mutation
		// path into the approved set is the listener's approveQueuedTrain call.
		// Re-captures the next unapproved id only after the listener has consumed the
		// previous one, so each train is approved exactly once.
		val dispatcher =
			object : Dispatcher {
				override fun decide(observed: DispatchObservation): List<DispatchDecision> {
					if (capturedTrainId.get() == null) {
						observed.unapprovedTrains.firstOrNull()?.let { capturedTrainId.set(it.trainId) }
					}
					return listOf(DispatchDecision.NoAction)
				}
			}

		val shuntingLoop = ShuntingLoop(context, endTime = 30L, dispatcher = dispatcher)
		shuntingLoopRef.set(shuntingLoop)
		shuntingLoop.controlStepListener =
			ControlStepListener {
				// Consume-once: approve the id captured on a previous tick's decide() and
				// clear it so it is never re-approved (which would throw in applyApproveTrain).
				capturedTrainId.getAndSet(null)?.let { trainId ->
					shuntingLoopRef.get()?.approveQueuedTrain(trainId)
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
		// ...and it entered the approved set. Since the dispatcher never approves,
		// this could only happen via approveQueuedTrain -> applyApproveTrain.
		assertThat(maxConcurrent).isGreaterThanOrEqualTo(1)
	}
}
