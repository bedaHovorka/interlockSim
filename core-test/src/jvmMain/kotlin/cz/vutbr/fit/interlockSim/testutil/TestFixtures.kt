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

import cz.vutbr.fit.interlockSim.util.Resources
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

	private fun mainResource(name: String): InputStream =
		Resources.read("cz/vutbr/fit/interlockSim/resource/$name").byteInputStream()

	private fun fixture(name: String): InputStream =
		Resources.read("cz/vutbr/fit/interlockSim/xml/fixtures/$name").byteInputStream()
}
