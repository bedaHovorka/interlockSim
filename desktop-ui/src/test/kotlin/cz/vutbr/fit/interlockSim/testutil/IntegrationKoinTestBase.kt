/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Base class for desktop-ui tests that need the integration Koin module
 */
package cz.vutbr.fit.interlockSim.testutil

import org.koin.core.module.Module

/**
 * A [KoinTestBase] that starts Koin with `integrationTestModule`.
 *
 * Thirteen desktop-ui test classes each wrote the same one-line override to get there (Issue #955,
 * cluster U4). Extending this instead states the intent — "this is an integration test" — in the
 * class header, where a reader already looks.
 */
abstract class IntegrationKoinTestBase : KoinTestBase() {
	final override fun getTestModule(): Module = integrationTestModule
}
