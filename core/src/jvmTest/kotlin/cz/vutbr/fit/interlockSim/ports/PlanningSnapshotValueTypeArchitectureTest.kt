/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.CandidateTarget
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.SeparatorKind
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * The planning snapshot carries values only (Issue #1468, #1467 spec §2): every type reachable from
 * the primary-constructor properties of [DispatchLoopSnapshot] is a primitive, a named enum, an
 * immutable `List`/`Set`/`Map` of those, or a `data class` reading in `ports` or a `sim` observation
 * type built of the same. No live object — in particular no [DynamicRailSwitch] — enters it.
 *
 * The walk uses kotlin-reflect over primary-constructor properties, recursing through generic
 * arguments and nullable types. It does not walk declared fields: those include the `$delegate`
 * `Lazy` fields of the [SimulationSnapshot] lookup maps, which derive only from captured lists.
 *
 * Each SP4 facet extends [EXPECTED_READING_TYPES] (#1468 adds [SwitchReading], #1470 adds
 * [ContentionReading]); a facet that adds a reading type without listing it here fails, so the list
 * stays the reviewed inventory.
 *
 * Issue #1470 (Goal 9B SP4.4) adds [ContentionReading]. Its "no `DynamicTrackBlock`" criterion is the
 * violation check: a `DynamicTrackBlock` anywhere in the walk is neither a value leaf nor a reading
 * data class, so it is reported; [aReadingThatHoldsALiveBlockIsRejected] proves it.
 */
class PlanningSnapshotValueTypeArchitectureTest {
	private data class Walk(
		val reached: MutableSet<KClass<*>> = linkedSetOf(),
		val violations: MutableList<String> = mutableListOf()
	)

	private fun walk(root: KClass<*>): Walk = Walk().also { visitClass(root, root.simpleName.orEmpty(), it) }

	private fun visitClass(
		cls: KClass<*>,
		path: String,
		walk: Walk
	) {
		if (!walk.reached.add(cls)) return
		val constructor = cls.primaryConstructor
		if (constructor == null) {
			walk.violations += "$path: ${cls.qualifiedName} has no primary constructor"
			return
		}
		val properties = cls.memberProperties.associateBy { it.name }
		for (parameter in constructor.parameters) {
			val property = properties[parameter.name]
			if (property == null) {
				walk.violations += "$path.${parameter.name}: constructor parameter is not a property"
				continue
			}
			visitType(property.returnType, "$path.${parameter.name}", walk)
		}
	}

	private fun visitType(
		type: KType,
		path: String,
		walk: Walk
	) {
		val cls = type.classifier as? KClass<*>
		when {
			cls == null -> walk.violations += "$path: type $type has no class classifier"
			cls in VALUE_CLASSES || cls in ALLOWED_ENUMS -> Unit
			cls in COLLECTION_CLASSES ->
				type.arguments.forEach { argument ->
					val argumentType = argument.type
					if (argumentType == null) {
						walk.violations += "$path: star projection in $type"
					} else {
						visitType(argumentType, "$path<>", walk)
					}
				}
			cls.isData && cls.java.packageName in READING_PACKAGES -> visitClass(cls, path, walk)
			else -> walk.violations += "$path: ${cls.qualifiedName} is not a value type"
		}
	}

	@Test
	fun theDispatchLoopSnapshotReachesOnlyValueTypes() {
		val walk = walk(DispatchLoopSnapshot::class)

		assertThat(walk.violations, name = "non-value types in the planning snapshot").isEmpty()
	}

	@Test
	fun theWalkReachesExactlyTheReviewedReadingTypes() {
		val walk = walk(DispatchLoopSnapshot::class)

		assertThat(walk.reached, name = "data classes reachable from DispatchLoopSnapshot")
			.containsExactlyInAnyOrder(*EXPECTED_READING_TYPES.toTypedArray())
	}

	@Test
	fun theSwitchFacetIsPartOfTheSnapshot() {
		assertThat(walk(DispatchLoopSnapshot::class).reached, name = "reachable data classes")
			.contains(SwitchReading::class)
	}

	@Test
	fun theContentionFacetIsPartOfTheSnapshot() {
		assertThat(walk(DispatchLoopSnapshot::class).reached, name = "reachable data classes")
			.contains(ContentionReading::class)
	}

	/** Proves the walker fails on a live object: a reading that holds a [DynamicRailSwitch]. */
	@Test
	fun aReadingThatHoldsALiveSwitchIsRejected() {
		val walk = walk(LiveSwitchHoldingReading::class)

		assertThat(walk.violations, name = "violations for a live-switch reading").isNotEmpty()
		assertThat(walk.violations.joinToString(), name = "violation text")
			.contains(DynamicRailSwitch::class.qualifiedName.orEmpty())
	}

	/** Proves the walker fails on a live track section: a reading that holds a [DynamicTrackBlock]. */
	@Test
	fun aReadingThatHoldsALiveBlockIsRejected() {
		val walk = walk(LiveBlockHoldingReading::class)

		assertThat(walk.violations, name = "violations for a live-block reading").isNotEmpty()
		assertThat(walk.violations.joinToString(), name = "violation text")
			.contains(DynamicTrackBlock::class.qualifiedName.orEmpty())
	}

	/** Not a real reading: a fixture for [aReadingThatHoldsALiveSwitchIsRejected]. */
	private data class LiveSwitchHoldingReading(
		val name: String,
		val switches: List<DynamicRailSwitch?>
	)

	/** Not a real reading: a fixture for [aReadingThatHoldsALiveBlockIsRejected]. */
	private data class LiveBlockHoldingReading(
		val sectionId: String,
		val block: DynamicTrackBlock?
	)

	private companion object {
		val VALUE_CLASSES: Set<KClass<*>> =
			setOf(String::class, Int::class, Long::class, Double::class, Boolean::class)

		/**
		 * The named enums of spec §2, plus [SeparatorKind]: the existing `sim` observation enum
		 * of [CandidateTarget] (Issue #1152), already a plain value.
		 */
		val ALLOWED_ENUMS: Set<KClass<*>> =
			setOf(Signal::class, TrackFacility.State::class, RailSwitch.Conf::class, SeparatorKind::class)

		val COLLECTION_CLASSES: Set<KClass<*>> = setOf(List::class, Set::class, Map::class)

		val READING_PACKAGES: Set<String> =
			setOf("cz.vutbr.fit.interlockSim.ports", "cz.vutbr.fit.interlockSim.sim")

		val EXPECTED_READING_TYPES: List<KClass<*>> =
			listOf(
				DispatchLoopSnapshot::class,
				QueuedTrainObservation::class,
				BlockInputObservation::class,
				CandidateTarget::class,
				SimulationSnapshot::class,
				SemaphoreReading::class,
				BlockOccupancyReading::class,
				TrainPositionReading::class,
				TimetableReading::class,
				TrainPerceptionReading::class,
				SwitchReading::class, // #1468
				ContentionReading::class // #1470
			)
	}
}
