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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.InputStream

/**
 * Tests for [PathReservationRegistry.mutationEpoch] (Issue #931 f2).
 *
 * The epoch is what makes the path-available condition cache sound: a waiting train may skip a
 * re-test only while this counter is unchanged. Two properties matter, and they fail in opposite
 * directions:
 *
 * - **Every mutation must bump.** A missed bump lets a train sleep through its own wake-up and
 *   stall until the Issue #943 error horizon, by which time the cause is long out of the log.
 * - **A no-op must not bump.** Extra bumps are safe but pay back the churn the change removed, and
 *   they hide a missing bump elsewhere behind coincidental invalidation.
 *
 * Every mutator and every no-mutation early return in the registry is covered below.
 *
 * @since Issue #931 f2 (Wave 3 — per-event pathfind churn)
 */
@Tag("integration-test")
class PathReservationRegistryEpochTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var context: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var blocks: List<DynamicTrackBlock>

	@BeforeEach
	fun setUp() {
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")
		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		registry = context.scope.get()

		val inOuts = context.getInOuts().toList()
		val navigator: TopologyNavigator = context.scope.get()
		blocks =
			navigator
				.findAllTopologicalPaths(inOuts[0], inOuts[1])
				.first()
				.mapNotNull { it.getTrackBlock() as? DynamicTrackBlock }
				.distinct()
	}

	@Nested
	@DisplayName("A mutation bumps the epoch")
	inner class Mutations {
		@Test
		@DisplayName("registerAtomic bumps")
		fun registerAtomicBumps() {
			assertBumps { registry.registerAtomic("train1", blocks) }
		}

		/** `register` delegates to `registerAtomic`, so its bump comes from there. */
		@Test
		@DisplayName("register bumps through its registerAtomic delegation")
		fun registerBumps() {
			assertBumps { registry.register("train1", blocks) }
		}

		@Test
		@DisplayName("unregister bumps")
		fun unregisterBumps() {
			registry.registerAtomic("train1", blocks)

			assertBumps { registry.unregister("train1") }
		}

		@Test
		@DisplayName("unregisterBlock bumps")
		fun unregisterBlockBumps() {
			registry.registerAtomic("train1", blocks)

			assertBumps { registry.unregisterBlock("train1", blocks.first()) }
		}

		@Test
		@DisplayName("registerPathInfo bumps on a first registration")
		fun registerPathInfoFirstBumps() {
			assertBumps { registry.registerPathInfo("train1", pathInfoFor()) }
		}

		/** The merge branch writes `trainToPathInfo` too, so it must bump as well. */
		@Test
		@DisplayName("registerPathInfo bumps again on a merge")
		fun registerPathInfoMergeBumps() {
			registry.registerPathInfo("train1", pathInfoFor())

			assertBumps { registry.registerPathInfo("train1", pathInfoFor()) }
		}

		@Test
		@DisplayName("restorePathInfo bumps when it restores a snapshot")
		fun restorePathInfoSnapshotBumps() {
			registry.registerPathInfo("train1", pathInfoFor())
			val snapshot = registry.getPathInfo("train1")

			assertBumps { registry.restorePathInfo("train1", snapshot) }
		}

		@Test
		@DisplayName("restorePathInfo bumps when it removes an entry")
		fun restorePathInfoRemovalBumps() {
			registry.registerPathInfo("train1", pathInfoFor())

			assertBumps { registry.restorePathInfo("train1", null) }
		}

		@Test
		@DisplayName("clear bumps")
		fun clearBumps() {
			registry.registerAtomic("train1", blocks)

			assertBumps { registry.clear() }
		}

		/**
		 * The switch maps are not read by `findReservedPathForTrain`, so bumping on them is pure
		 * over-invalidation. It is deliberate: it costs one recomputation and removes a whole class
		 * of future mistake, and this test stops a later "optimisation" quietly dropping it.
		 */
		@Test
		@DisplayName("registerSwitches bumps even though the path evaluation ignores switches")
		fun registerSwitchesBumps() {
			assertBumps { registry.registerSwitches("train1", listOf(switchVA())) }
		}

		@Test
		@DisplayName("unregisterSwitches bumps")
		fun unregisterSwitchesBumps() {
			registry.registerSwitches("train1", listOf(switchVA()))

			assertBumps { registry.unregisterSwitches("train1") }
		}

		@Test
		@DisplayName("unregisterSwitch bumps")
		fun unregisterSwitchBumps() {
			val switch = switchVA()
			registry.registerSwitches("train1", listOf(switch))

			assertBumps { registry.unregisterSwitch("train1", switch) }
		}
	}

	@Nested
	@DisplayName("A no-op leaves the epoch alone")
	inner class NoOps {
		@Test
		@DisplayName("a fresh registry starts at zero")
		fun startsAtZero() {
			assertThat(registry.mutationEpoch, "initial epoch").isEqualTo(0L)
		}

		@Test
		@DisplayName("registerAtomic rejected by a conflict does not bump")
		fun conflictingRegisterAtomicDoesNotBump() {
			registry.registerAtomic("train1", blocks)

			assertDoesNotBump { registry.registerAtomic("train2", blocks) }
		}

		@Test
		@DisplayName("unregister of an unknown train does not bump")
		fun unregisterUnknownTrainDoesNotBump() {
			assertDoesNotBump { registry.unregister("nobody") }
		}

		@Test
		@DisplayName("unregisterBlock of a block owned by another train does not bump")
		fun unregisterForeignBlockDoesNotBump() {
			registry.registerAtomic("train1", blocks)

			assertDoesNotBump { registry.unregisterBlock("train2", blocks.first()) }
		}

		@Test
		@DisplayName("unregisterSwitches of a train with no switches does not bump")
		fun unregisterSwitchesOfUnknownTrainDoesNotBump() {
			assertDoesNotBump { registry.unregisterSwitches("nobody") }
		}

		@Test
		@DisplayName("unregisterSwitch of a switch owned by another train does not bump")
		fun unregisterForeignSwitchDoesNotBump() {
			val switch = switchVA()
			registry.registerSwitches("train1", listOf(switch))

			assertDoesNotBump { registry.unregisterSwitch("train2", switch) }
		}

		@Test
		@DisplayName("reads never bump")
		fun readsDoNotBump() {
			registry.registerAtomic("train1", blocks)
			registry.registerPathInfo("train1", pathInfoFor())

			assertDoesNotBump {
				registry.getOwner(blocks.first())
				registry.getBlocks("train1")
				registry.getPathInfo("train1")
				registry.isRegistered(blocks.first())
				registry.trainCount()
				registry.blockCount()
			}
		}
	}

	private fun assertBumps(action: () -> Unit) {
		val before = registry.mutationEpoch
		action()
		assertThat(registry.mutationEpoch, "epoch after a mutation").isGreaterThan(before)
	}

	private fun assertDoesNotBump(action: () -> Unit) {
		val before = registry.mutationEpoch
		action()
		assertThat(registry.mutationEpoch, "epoch after a no-op").isEqualTo(before)
	}

	/** The `vyhybna.xml` switch at grid (15, 8) — the one `SwitchConfigurationTest` calls `vA`. */
	private fun switchVA(): DynamicRailSwitch = elementAt(15, 8)

	private inline fun <reified T : Cell> elementAt(
		x: Int,
		y: Int
	): T {
		val cell =
			context.getRailWayNetGrid()[Point(x, y)]
				?: throw IllegalArgumentException("No cell at ($x, $y)")
		return Util.assertInstanceOf(cell)
	}

	/** A [PathInfo] over one full topological route through the network. */
	private fun pathInfoFor(): PathInfo {
		val inOuts = context.getInOuts().toList()
		val navigator: TopologyNavigator = context.scope.get()
		val arrayPath = ArrayPath(context)
		navigator.findAllTopologicalPaths(inOuts[0], inOuts[1]).first().forEach { element ->
			when (element) {
				is DynamicPathSeparator -> arrayPath.add(element)
				is TrackSection -> arrayPath.add(element)
				else -> throw IllegalArgumentException("Invalid path element: $element")
			}
		}
		return PathInfo(
			start = context.toDynamic(inOuts[0]),
			target = context.toDynamic(inOuts[1]),
			reservedPath = arrayPath,
			entryDirections = emptyMap()
		)
	}
}
