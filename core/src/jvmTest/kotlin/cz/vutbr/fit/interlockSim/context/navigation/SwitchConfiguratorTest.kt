/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SwitchConfigurator], the switch-configuration seam extracted from
 * [DefaultPathReservationService] (Issue #1170, SP12.4).
 *
 * Runs on `vyhybna.xml`: `zA → vA → k1 → doB1` is the same-position route over vA in
 * [Conf.MAIN]; `doB1 → doB2` is the physically impossible sibling-branch diversion (Issue #742).
 *
 * The [RegisterSwitches] group PINS today's `registerSwitches` behaviour, including the
 * born-stale claim on a switch the candidate never traverses. Issue #1173 changes that
 * behaviour on purpose; its diff must show up here.
 */
@DisplayName("SwitchConfigurator (Issue #1170)")
class SwitchConfiguratorTest : PathReservationServiceTestBase() {
	private lateinit var pathInfoBuilder: PathInfoBuilder
	private lateinit var configurator: SwitchConfigurator

	private lateinit var zA: DynamicPathSeparator
	private lateinit var doB1: DynamicPathSeparator
	private lateinit var doB2: DynamicPathSeparator
	private lateinit var vA: DynamicRailSwitch
	private lateinit var vB: DynamicRailSwitch

	@BeforeEach
	fun setUpConfigurator() {
		pathInfoBuilder = simulationContext.scope.get()
		configurator = SwitchConfigurator(environment, registry)
		zA = findSemaphoreByName("zA")
		doB1 = findSemaphoreByName("doB1")
		doB2 = findSemaphoreByName("doB2")
		vA = switchNamed("vA")
		vB = switchNamed("vB")
		if (vA.conf != Conf.MAIN) {
			vA.changeConf()
		}
	}

	private fun switchNamed(name: String): DynamicRailSwitch =
		simulationContext.cellsOfType<DynamicRailSwitch>().single { it.name == name }

	/** PathInfo of the shortest topological path from [start] to [target]. */
	private fun pathInfo(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	): PathInfo {
		val sections = navigator.findAllTopologicalPaths(start, target).minBy { it.size }
		return pathInfoBuilder.buildPathInfo(start, target, sections)
	}

	/**
	 * PathInfo `zA → vA` that ENDS at vA: the candidate lists vA but never traverses it (no track
	 * after it), so configuration skips it leniently. Production routes never end at a switch
	 * (Issue #938); this is the smallest shape that holds a listed-but-not-traversed switch.
	 */
	private fun pathInfoEndingAtVA(): PathInfo {
		val firstSection = navigator.findAllTopologicalPaths(zA, doB1).minBy { it.size }.first()
		val info = pathInfoBuilder.buildPathInfo(zA, vA, listOf(firstSection))
		assertThat(info.reservedPath.last(), name = "last path element").isSameInstanceAs(vA)
		return info
	}

	@Nested
	@DisplayName("extractUniqueSwitches")
	inner class ExtractUniqueSwitches {
		@Test
		fun `lists every switch of the path once`() {
			assertThat(configurator.extractUniqueSwitches(pathInfo(zA, doB1))).containsExactly(vA)
		}

		@Test
		fun `a path without a switch yields an empty list`() {
			val info = pathInfoBuilder.buildPathInfo(zA, zA, emptyList())

			assertThat(configurator.extractUniqueSwitches(info)).isEmpty()
		}
	}

	@Nested
	@DisplayName("configureSwitchesInPath")
	inner class ConfigureSwitchesInPath {
		@Test
		fun `a traversable path configures and locks its switch without registering it`() {
			val outcome = configurator.configureSwitchesInPath(TRAIN, pathInfo(zA, doB1))

			assertThat(outcome).isEqualTo(SwitchConfigOutcome.Configured)
			assertThat(vA.conf).isEqualTo(Conf.MAIN)
			assertThat(vA.locked).isTrue()
			assertThat(registry.getSwitchOwner(vA), name = "registration is a separate step").isNull()
		}

		@Test
		fun `the sibling-branch diversion is Unconfigurable (Issue 742)`() {
			val outcome = configurator.configureSwitchesInPath(TRAIN, pathInfo(doB1, doB2))

			assertThat(outcome).isEqualTo(SwitchConfigOutcome.Unconfigurable)
		}

		@Test
		fun `a switch locked in the other position is Locked contention (Issue 1065)`() {
			vA.changeConf()
			registry.registerSwitches(OTHER, listOf(vA))

			val outcome = configurator.configureSwitchesInPath(TRAIN, pathInfo(zA, doB1))

			assertThat(outcome).isEqualTo(SwitchConfigOutcome.Locked(vA, OTHER, Conf.BRANCH, Conf.MAIN))
			assertThat(vA.conf, name = "the held position is untouched").isEqualTo(Conf.BRANCH)
		}

		@Test
		fun `a switch registered to another train in the same position is Locked contention (Issue 1076)`() {
			registry.registerSwitches(OTHER, listOf(vA))

			val outcome = configurator.configureSwitchesInPath(TRAIN, pathInfo(zA, doB1))

			assertThat(outcome).isEqualTo(SwitchConfigOutcome.Locked(vA, OTHER, Conf.MAIN, Conf.MAIN))
		}

		@Test
		fun `a foreign owner does not mask an Unconfigurable switch (Issue 1076 review)`() {
			registry.registerFlankSwitches(OTHER, listOf(vA, vB))

			val outcome = configurator.configureSwitchesInPath(TRAIN, pathInfo(doB1, doB2))

			assertThat(outcome).isEqualTo(SwitchConfigOutcome.Unconfigurable)
		}

		@Test
		fun `a switch the path does not traverse is skipped and left unlocked`() {
			val outcome = configurator.configureSwitchesInPath(TRAIN, pathInfoEndingAtVA())

			assertThat(outcome).isEqualTo(SwitchConfigOutcome.Configured)
			assertThat(vA.locked).isFalse()
		}
	}

	/**
	 * PIN of today's behaviour (Issue #1170). Issue #1173 changes the born-stale case on purpose;
	 * update these tests in that PR, so the behaviour change is a visible diff.
	 */
	@Nested
	@DisplayName("registerSwitches (pinned behaviour)")
	inner class RegisterSwitches {
		@Test
		fun `registers and locks every candidate switch the train does not see owned by another train`() {
			configurator.registerSwitches(TRAIN, listOf(vA, vB))

			assertThat(registry.getSwitches(TRAIN)).containsExactlyInAnyOrder(vA, vB)
			assertThat(vA.locked).isTrue()
			assertThat(vB.locked).isTrue()
		}

		@Test
		fun `skips a switch owned by another train instead of throwing`() {
			registry.registerSwitches(OTHER, listOf(vB))

			configurator.registerSwitches(TRAIN, listOf(vA, vB))

			assertThat(registry.getSwitches(TRAIN)).containsExactly(vA)
			assertThat(registry.getSwitchOwner(vB)).isEqualTo(OTHER)
		}

		@Test
		fun `born stale - a listed switch the candidate never traverses is registered with no block held`() {
			val info = pathInfoEndingAtVA()
			val switches = configurator.extractUniqueSwitches(info)
			assertThat(configurator.configureSwitchesInPath(TRAIN, info)).isEqualTo(SwitchConfigOutcome.Configured)
			assertThat(vA.locked, name = "configuration skipped vA").isFalse()

			configurator.registerSwitches(TRAIN, switches)

			// Today vA is claimed and locked although it was never configured and TRAIN holds no
			// block bounded by it: the claim is stale the moment it is made.
			assertThat(registry.getSwitchOwner(vA)).isEqualTo(TRAIN)
			assertThat(vA.locked).isTrue()
			assertThat(registry.getBlocks(TRAIN)).isEmpty()
			assertThat(registry.isStaleSwitchOwnership(vA), name = "born stale").isTrue()
		}

		@Test
		fun `born stale - the claim lasts until another train's candidate reclaims it`() {
			configurator.registerSwitches(TRAIN, configurator.extractUniqueSwitches(pathInfoEndingAtVA()))
			assertThat(registry.getSwitchOwner(vA), name = "born-stale claim").isEqualTo(TRAIN)

			configurator.reclaimStaleForeignSwitchOwnership(OTHER, listOf(vA))

			assertThat(registry.getSwitchOwner(vA)).isNull()
			assertThat(vA.locked).isFalse()
		}
	}

	@Nested
	@DisplayName("pre-configuration clean-up (Step 2e.5)")
	inner class PreConfigurationCleanUp {
		@Test
		fun `releaseOrphanSwitchLocks unlocks an ownerless lock only`() {
			vA.lock()
			registry.registerSwitches(OTHER, listOf(vB))

			configurator.releaseOrphanSwitchLocks(listOf(vA, vB))

			assertThat(vA.locked, name = "orphan").isFalse()
			assertThat(vB.locked, name = "owned").isTrue()
		}

		@Test
		fun `reclaimStaleForeignSwitchOwnership reclaims a stale foreign claim but keeps own and flank claims`() {
			registry.registerSwitches(OTHER, listOf(vA))
			registry.registerFlankSwitches(FLANK_OWNER, listOf(vB))

			configurator.reclaimStaleForeignSwitchOwnership(TRAIN, listOf(vA, vB))

			assertThat(registry.getSwitchOwner(vA), name = "stale foreign claim").isNull()
			assertThat(registry.getSwitchOwner(vB), name = "flank claim").isEqualTo(FLANK_OWNER)

			registry.registerSwitches(TRAIN, listOf(vA))
			configurator.reclaimStaleForeignSwitchOwnership(TRAIN, listOf(vA))
			assertThat(registry.getSwitchOwner(vA), name = "own claim").isEqualTo(TRAIN)
		}
	}

	@Nested
	@DisplayName("releaseCandidateSwitches")
	inner class ReleaseCandidateSwitches {
		@Test
		fun `unregisters an own claim and unlocks an ownerless lock`() {
			registry.registerSwitches(TRAIN, listOf(vA))
			vB.lock()

			configurator.releaseCandidateSwitches(TRAIN, listOf(vA, vB))

			assertThat(registry.getSwitches(TRAIN)).isEmpty()
			assertThat(vA.locked).isFalse()
			assertThat(vB.locked).isFalse()
		}

		@Test
		fun `leaves a switch owned by another train locked and registered`() {
			registry.registerSwitches(OTHER, listOf(vA))

			configurator.releaseCandidateSwitches(TRAIN, listOf(vA))

			assertThat(registry.getSwitchOwner(vA)).isEqualTo(OTHER)
			assertThat(vA.locked).isTrue()
		}
	}

	private companion object {
		const val TRAIN = "train1"
		const val OTHER = "other"
		const val FLANK_OWNER = "flank"
	}
}
