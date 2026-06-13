package cz.vutbr.fit.interlockSim.testutil.providers

import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Type
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import java.util.stream.Stream

/**
 * Provides test arguments for DynamicRailSwitch.getActiveSegments() tests.
 *
 * Each row encodes: Type, SpatialType, mainSeg1, mainSeg2, branchSeg1, branchSeg2
 */
class SwitchActiveSegmentsProvider : ArgumentsProvider {
	override fun provideArguments(context: ExtensionContext): Stream<Arguments> =
		Stream.of(
			// HORIZONTAL switch types
			Arguments.of(Type.SIMPLE_LEFT_FALSE, SpatialType.HORIZONTAL, Segment.A, Segment.F, Segment.A, Segment.E),
			Arguments.of(Type.SIMPLE_RIGHT_FALSE, SpatialType.HORIZONTAL, Segment.A, Segment.F, Segment.A, Segment.G),
			Arguments.of(Type.SIMPLE_LEFT_TRUE, SpatialType.HORIZONTAL, Segment.A, Segment.F, Segment.F, Segment.D),
			Arguments.of(Type.SIMPLE_RIGHT_TRUE, SpatialType.HORIZONTAL, Segment.A, Segment.F, Segment.F, Segment.B),
			// VERTICAL switch types
			Arguments.of(Type.SIMPLE_LEFT_FALSE, SpatialType.VERTICAL, Segment.C, Segment.H, Segment.C, Segment.G),
			Arguments.of(Type.SIMPLE_RIGHT_FALSE, SpatialType.VERTICAL, Segment.C, Segment.H, Segment.C, Segment.D),
			Arguments.of(Type.SIMPLE_LEFT_TRUE, SpatialType.VERTICAL, Segment.H, Segment.C, Segment.H, Segment.B),
			Arguments.of(Type.SIMPLE_RIGHT_TRUE, SpatialType.VERTICAL, Segment.C, Segment.H, Segment.H, Segment.E)
		)
}
