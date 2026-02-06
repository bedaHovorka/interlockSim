/* Brno University of Technology
 * Faculty of Information Technology
 *
 * Railway Interlocking Simulator - Performance Benchmarks
 *
 * Issue #216: Migrate Array2DMapTest.testSpeed() to JMH
 */
package cz.vutbr.fit.interlockSim.benchmarks

import cz.vutbr.fit.interlockSim.util.Array2DMap
import cz.vutbr.fit.interlockSim.util.Point
import org.openjdk.jmh.annotations.*
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Performance comparison between Array2DMap and TreeMap for railway grid cell lookups.
 *
 * Original test conclusion: "The speedup is almost none, but at least when calling
 * get(Int,Int) directly, the creation of key objects is eliminated."
 *
 * Run with: ./gradlew jmh
 * Results: build/reports/jmh/results.txt
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = ["-ea"])
open class GridStoragePerformance {

    // Railway grid dimensions for simulation
    private val gridDimension = 1000
    
    // Test data: coordinates to lookup in grid
    private lateinit var testCoordinates: List<Point>
    
    // Storage implementations to benchmark
    private lateinit var customGridMap: Array2DMap<Int>
    private lateinit var standardTreeMap: TreeMap<Point, Int>

    @Setup(Level.Trial)
    fun prepareTestData() {
        // Generate consistent test coordinates using seeded random
        val coordinateGenerator = Random(42)
        testCoordinates = List(gridDimension) {
            Point(
                coordinateGenerator.nextInt(gridDimension),
                coordinateGenerator.nextInt(gridDimension)
            )
        }
        
        // Initialize Array2DMap with test data
        customGridMap = Array2DMap()
        testCoordinates.forEachIndexed { idx, coord ->
            customGridMap[coord] = idx
        }
        
        // Initialize TreeMap with same data and comparator
        standardTreeMap = TreeMap(Array2DMap.POINT_COMPARATOR)
        testCoordinates.forEachIndexed { idx, coord ->
            standardTreeMap[coord] = idx
        }
    }

    @Benchmark
    fun measureCustomGridLookup(): Int {
        // Test Array2DMap performance for railway grid cell access
        var checksum = 0
        for (coord in testCoordinates) {
            checksum += customGridMap[coord] ?: 0
        }
        return checksum
    }

    @Benchmark
    fun measureStandardTreeLookup(): Int {
        // Baseline: TreeMap performance for comparison
        var checksum = 0
        for (coord in testCoordinates) {
            checksum += standardTreeMap[coord] ?: 0
        }
        return checksum
    }
}
