/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: what the Issue #994 race probe observed.
 */
package cz.vutbr.fit.interlockSim.testutil

/** What [probeConcurrentReads] observed: total successful reads and one representative per failure type. */
class ConcurrentReadProbeResult(
	val totalReads: Long,
	val failures: List<String>
)
