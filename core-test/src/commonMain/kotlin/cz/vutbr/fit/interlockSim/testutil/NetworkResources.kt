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

/**
 * Canonical XML network definitions shared across test fixtures and embedded binary resources.
 *
 * Single source of truth for all XML railway network strings used in tests and native CLI examples.
 * Consumed by:
 * - [CommonTestFixtures] (test fixtures in :core, :desktop-ui, :fast-sim tests)
 * - [cz.vutbr.fit.interlockSim.fastsim.EmbeddedResources] (:fast-sim linuxX64 embedded examples)
 */
object NetworkResources {

	/** Shunting loop — canonical test fixture (vyhybna.xml). Used by ShuntingLoop simulation. */
	val VYHYBNA_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="100" Y="100">
		|  <RailSemaphore X="16" Y="8" SpatialType="HORIZONTAL" orientation="true" name="doA1"/>
		|  <InOut X="30" Y="8" SpatialType="HORIZONTAL" orientation="true" name="B"/>
		|  <RailSemaphore X="17" Y="9" SpatialType="HORIZONTAL" orientation="true" name="doA2"/>
		|  <RailSwitch X="26" Y="8" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_TRUE" name="vB"/>
		|  <InOut X="11" Y="8" SpatialType="HORIZONTAL" orientation="false" name="A"/>
		|  <RailSemaphore X="24" Y="9" SpatialType="HORIZONTAL" orientation="false" name="doB2"/>
		|  <RailSwitch X="15" Y="8" SpatialType="HORIZONTAL" Type="SIMPLE_RIGHT_FALSE" name="vA"/>
		|  <RailSemaphore X="14" Y="8" SpatialType="HORIZONTAL" orientation="false" name="zA"/>
		|  <RailSemaphore X="25" Y="8" SpatialType="HORIZONTAL" orientation="false" name="doB1"/>
		|  <RailSemaphore X="27" Y="8" SpatialType="HORIZONTAL" orientation="true" name="zB"/>
		|  <SimpleTrackBlock fromX="16" fromY="8" toX="25" toY="8" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="30" fromY="8" toX="27" toY="8" fromSegment="A" toSegment="F" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="17" fromY="9" toX="24" toY="9" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="15" fromY="8" toX="16" toY="8" fromSegment="F" toSegment="A" length="5.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="14" fromY="8" toX="15" toY="8" fromSegment="F" toSegment="A" length="5.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="24" fromY="9" toX="26" toY="8" fromSegment="F" toSegment="D" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="25" fromY="8" toX="26" toY="8" fromSegment="F" toSegment="A" length="5.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="17" fromY="9" toX="15" toY="8" fromSegment="A" toSegment="G" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="11" fromY="8" toX="14" toY="8" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|  <SimpleTrackBlock fromX="27" fromY="8" toX="26" toY="8" fromSegment="A" toSegment="F" length="5.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
		|</net>
	""".trimMargin()

	/** Two-node linear track connecting InOut A and InOut B. */
	val LINEAR_TRACK_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="100" Y="100">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="A"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B"/>
		|	<SimpleTrackBlock fromX="10" fromY="10" toX="20" toY="10" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="25.0" maxSpeedto="25.0"/>
		|</net>
	""".trimMargin()

	/** Minimal two-InOut network without any track blocks. */
	val MINIMAL_NETWORK_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="100" Y="100">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="A"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B"/>
		|</net>
	""".trimMargin()

	/** Network with a single InOut element (used for InOut validation tests). */
	val SINGLE_INOUT_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="10" Y="10">
		|  <InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="false" name="OnlyOne"/>
		|</net>
	""".trimMargin()

	/** Empty network with no InOut elements (used for validation error tests). */
	val ZERO_INOUTS_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="10" Y="10">
		|</net>
	""".trimMargin()

	/** Network with a simple right-diverging switch. */
	val SWITCH_BASIC_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="100" Y="100">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="IN"/>
		|	<RailSwitch X="15" Y="10" SpatialType="HORIZONTAL" Type="SIMPLE_RIGHT_FALSE"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="OUT_PLUS"/>
		|	<InOut X="20" Y="11" SpatialType="HORIZONTAL" orientation="true" name="OUT_MINUS"/>
		|	<SimpleTrackBlock fromX="10" fromY="10" toX="15" toY="10" fromSegment="F" toSegment="A" length="50.0" maxSpeedfrom="25.0" maxSpeedto="25.0"/>
		|	<SimpleTrackBlock fromX="15" fromY="10" toX="20" toY="10" fromSegment="F" toSegment="A" length="50.0" maxSpeedfrom="25.0" maxSpeedto="25.0"/>
		|	<SimpleTrackBlock fromX="15" fromY="10" toX="20" toY="11" fromSegment="G" toSegment="A" length="60.0" maxSpeedfrom="20.0" maxSpeedto="20.0"/>
		|</net>
	""".trimMargin()

	/** Network with a horizontal semaphore. */
	val SEMAPHORE_BASIC_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="100" Y="100">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="IN"/>
		|	<RailSemaphore X="15" Y="10" SpatialType="HORIZONTAL" orientation="true"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="OUT"/>
		|	<SimpleTrackBlock fromX="10" fromY="10" toX="15" toY="10" fromSegment="F" toSegment="A" length="50.0" maxSpeedfrom="25.0" maxSpeedto="25.0"/>
		|	<SimpleTrackBlock fromX="15" fromY="10" toX="20" toY="10" fromSegment="F" toSegment="A" length="50.0" maxSpeedfrom="25.0" maxSpeedto="25.0"/>
		|</net>
	""".trimMargin()

	/** Two parallel tracks with independent InOut pairs. */
	val TWO_TRACKS_PARALLEL_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="100" Y="100">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="A1"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B1"/>
		|	<InOut X="10" Y="12" SpatialType="HORIZONTAL" orientation="false" name="A2"/>
		|	<InOut X="20" Y="12" SpatialType="HORIZONTAL" orientation="true" name="B2"/>
		|	<SimpleTrackBlock fromX="10" fromY="10" toX="20" toY="10" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="30.0" maxSpeedto="30.0"/>
		|	<SimpleTrackBlock fromX="10" fromY="12" toX="20" toY="12" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="30.0" maxSpeedto="30.0"/>
		|</net>
	""".trimMargin()

	/** Small grid with two InOut elements (used for grid/cell tests). */
	val EMPTY_GRID_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="50" Y="50">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="A"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B"/>
		|</net>
	""".trimMargin()
}
