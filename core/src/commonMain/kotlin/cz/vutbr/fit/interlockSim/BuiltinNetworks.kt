/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

/**
 * XML string literals for built-in railway network examples.
 *
 * Single source of truth for XML used both in the native binary ([cz.vutbr.fit.interlockSim.fastsim.EmbeddedResources])
 * and in test fixtures ([cz.vutbr.fit.interlockSim.testutil.NetworkResources]).
 *
 * Only networks required by the production binary live here. Test-only fixtures stay in :core-test.
 *
 * @since Issue #415 (fast-sim native CLI)
 */
object BuiltinNetworks {

	/** Shunting loop network (vyhybna.xml). Used by ShuntingLoop simulation and fast-sim embedded example. */
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
}
