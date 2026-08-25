/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory

import cz.vutbr.fit.interlockSim.util.Resources
import cz.vutbr.fit.interlockSim.util.Util
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import java.io.InputStream

/**
 * Centralized JVM-only test fixture repository.
 *
 * Returns `InputStream` for callers that feed `XMLContextFactory.createContext(stream)`
 * — the multiplatform [Resources.read] returns `String` but `XMLContextFactory`'s
 * public API is `InputStream`-based, so this object bridges `Resources.read(path)
 * .byteInputStream()` at one place instead of at every call site.
 *
 * Consumed by `:core/jvmTest` and `:desktop-ui/test` via `implementation(project(":core-test"))`.
 *
 * XML content source of truth: `core/src/commonMain/resources/cz/vutbr/fit/interlockSim/
 * resource/` (production assets) and `core-test/src/commonMain/resources/cz/vutbr/fit/
 * interlockSim/xml/fixtures/` (test fixtures).
 */
object TestFixtures {
	fun loadShuntingXml(): InputStream = mainResource("vyhybna.xml")

	fun loadLinearTrackXml(): InputStream = fixture("linear-track.xml")

	fun loadMinimalNetworkXml(): InputStream = fixture("minimal-network.xml")

	fun loadSwitchBasicXml(): InputStream = fixture("switch-basic.xml")

	fun loadSemaphoreBasicXml(): InputStream = fixture("semaphore-basic.xml")

	fun loadTwoTracksParallelXml(): InputStream = fixture("two-tracks-parallel.xml")

	fun loadEmptyGridXml(): InputStream = fixture("empty-grid.xml")

	fun loadPrahaHlavniNadraziXml(): InputStream = fixture("praha-hlavni-nadrazi.xml")

	fun loadRudyUjezdXml(): InputStream = fixture("rudyUjezd.xml")

	/**
	 * Two-parallel-route network (Issue #598 Goal 2 SP6).
	 *
	 * Minimal diamond topology: A → [swA] → {k1 (400 m) | k2 (800 m)} → [swB] → B.
	 * Exactly two routes exist; k1 is cheapest by length and by element count.
	 */
	fun loadParallelRoutesXml(): InputStream = fixture("parallel-routes.xml")

	fun loadLegacyNetworkNoNamesXml(): InputStream = fixture("legacy-network-no-names.xml")

	fun loadValidSpecialCharsNamesXml(): InputStream = fixture("valid-special-chars-names.xml")

	fun loadInvalidMalformedXml(): InputStream = fixture("invalid-malformed-xml.xml")

	fun loadInvalidMissingGridSizeXml(): InputStream = fixture("invalid-missing-grid-size.xml")

	fun loadInvalidMissingSpatialTypeXml(): InputStream = fixture("invalid-missing-spatial-type.xml")

	fun loadInvalidWrongRootElementXml(): InputStream = fixture("invalid-wrong-root-element.xml")

	fun loadInvalidNameSpecialCharsXml(): InputStream = fixture("invalid-name-special-chars.xml")

	fun loadInvalidNameTooLongXml(): InputStream = fixture("invalid-name-too-long.xml")

	fun loadSwitchBetweenSemaphoresXml(): InputStream = fixture("switch-between-semaphores.xml")

	/**
	 * Issue #903 regression fixture: a candidate ordering where the GEOMETRIC candidate
	 * (unconfigurable switch) is enumerated FIRST and a CONTENTION candidate SECOND,
	 * exercising the `recordAttemptResult` priority in `reservePathToAnyNextSemaphore`.
	 * See `geometric-priority.xml` header comment for the topology.
	 */
	fun loadGeometricPriorityXml(): InputStream = fixture("geometric-priority.xml")

	/** Load a named InOut-validation fixture (e.g. "zero-inouts.xml", "single-inout.xml"). */
	fun loadInvalidInOutXml(fixtureName: String): InputStream = fixture(fixtureName)

	/**
	 * Loads `vyhybna.xml` into a fresh [EditingContext] through [editingContextFactory].
	 *
	 * The XML stream is closed for the caller — the `use { … }` + `as EditingContext` pair was
	 * repeated verbatim in dozens of test classes (Issue #955).
	 */
	fun loadShuntingEditingContext(editingContextFactory: JvmEditingContextFactory): EditingContext =
		loadShuntingXml().use { xmlStream ->
			Util.assertInstanceOf<EditingContext>(editingContextFactory.createContext(xmlStream))
		}

	/**
	 * Loads `vyhybna.xml` into a fresh [DefaultSimulationContext].
	 *
	 * Replaces the two-step "load XML → [EditingContext] → [DefaultSimulationContext]" chain that
	 * was repeated in ~67 test classes across `:core`, `:desktop-ui` and `:dispatcher-agent`
	 * (Issue #955). Pass the Koin-injected factories so the loaded context is wired exactly as the
	 * test's module configured it.
	 *
	 * The returned context owns a Koin scope and must be closed by the caller (directly, via
	 * `use { … }`, or by assigning it to `KoinTestBase.testContext`).
	 *
	 * @param simulationContextFactory factory performing the editing → simulation transformation
	 * @param editingContextFactory when non-null, parses the XML explicitly; when `null` the
	 *   [simulationContextFactory]'s own stream overload is used (it delegates to the editing
	 *   factory it was constructed with). Both paths consume the stream synchronously before
	 *   returning, so closing it once the `use { … }` block exits is safe.
	 * @param warmUpDynamicWrappers when `true`, calls [DefaultSimulationContext.getInOuts] before
	 *   returning — the dynamic wrapper map must be initialised before a `ShuntingLoop` is built
	 */
	fun loadShuntingSimulationContext(
		simulationContextFactory: SimulationContextFactory,
		editingContextFactory: JvmEditingContextFactory? = null,
		warmUpDynamicWrappers: Boolean = false
	): DefaultSimulationContext {
		val context =
			loadShuntingXml().use { xmlStream ->
				val created =
					if (editingContextFactory == null) {
						simulationContextFactory.createContext(xmlStream)
					} else {
						val editingContext =
							Util.assertInstanceOf<EditingContext>(editingContextFactory.createContext(xmlStream))
						simulationContextFactory.createContext(editingContext)
					}
				Util.assertInstanceOf<DefaultSimulationContext>(created)
			}
		if (warmUpDynamicWrappers) {
			context.getInOuts()
		}
		return context
	}

	/**
	 * Loads `vyhybna.xml` into a fresh [DefaultSimulationContext] **without Koin**.
	 *
	 * This is the non-injected sibling of [loadShuntingSimulationContext]: it builds the context
	 * through [XMLContextFactory] plus [DefaultSimulationContext.fromEditingContext] instead of a
	 * Koin-provided [SimulationContextFactory]. Tests in `:dispatcher-agent` and the headless
	 * `:desktop-ui` timing suites each carried a private copy of exactly this chain, together with
	 * their own `XMLContextFactory` / [DefaultSimulationProcessFactory] pair (Issue #955). The
	 * defaults reproduce that wiring verbatim, so migrated call sites keep their behaviour.
	 *
	 * Unless [initializeDynamicMapping] is set, call [DefaultSimulationContext.getInOuts] on the
	 * result before constructing a `ShuntingLoop` — the dynamic wrapper map must be initialised first.
	 *
	 * The caller owns the returned context and must close it (`use { }` or an `@AfterEach` teardown).
	 *
	 * @param initializeDynamicMapping when `true`, routes through [ContextTransformer], which
	 *   eagerly initialises the dynamic wrapper mapping — the path the animation tests take
	 *   because they read wrappers before `run()`
	 */
	fun newShuntingSimulationContext(
		xmlContextFactory: JvmEditingContextFactory = XMLContextFactory(),
		processFactory: SimulationProcessFactory = DefaultSimulationProcessFactory(),
		initializeDynamicMapping: Boolean = false
	): DefaultSimulationContext {
		val editingContext = loadShuntingEditingContext(xmlContextFactory)
		return if (initializeDynamicMapping) {
			Util.assertInstanceOf<DefaultSimulationContext>(
				ContextTransformer.createSimulationContext(editingContext, processFactory)
			)
		} else {
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}
	}

	private fun mainResource(name: String): InputStream =
		Resources.read("cz/vutbr/fit/interlockSim/resource/$name").byteInputStream()

	private fun fixture(name: String): InputStream =
		Resources.read("cz/vutbr/fit/interlockSim/xml/fixtures/$name").byteInputStream()
}
