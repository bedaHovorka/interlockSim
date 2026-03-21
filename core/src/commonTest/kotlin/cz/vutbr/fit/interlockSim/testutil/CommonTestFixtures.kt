/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Common Test Fixtures for KMP commonTest
 *
 * Provides embedded XML fixture strings and helper functions
 * for cross-platform tests (JVM + Native).
 *
 * @since 2026-03 (KMP Task 6 — Phase 2 test migration)
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.xml.XmlContextReader

/**
 * Cross-platform test fixtures with embedded XML strings.
 *
 * Replaces [TestFixtures] (JVM-only, file-based) for commonTest usage.
 * All XML strings are canonical representations of the test fixture files
 * from `src/test/resources/`.
 */
object CommonTestFixtures {

	/** Shunting loop — canonical test fixture (vyhybna.xml) */
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

	/** Linear track — two InOuts connected by a single track block */
	val LINEAR_TRACK_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="100" Y="100">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="A"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B"/>
		|	<SimpleTrackBlock fromX="10" fromY="10" toX="20" toY="10" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="25.0" maxSpeedto="25.0"/>
		|</net>
	""".trimMargin()

	/** Minimal network — two InOuts, no track blocks */
	val MINIMAL_NETWORK_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="100" Y="100">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="A"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B"/>
		|</net>
	""".trimMargin()

	/** Single InOut — minimum valid network (bidirectional operation) */
	val SINGLE_INOUT_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="10" Y="10">
		|  <InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="false" name="OnlyOne"/>
		|</net>
	""".trimMargin()

	/** Zero InOuts — invalid network, should fail validation */
	val ZERO_INOUTS_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="10" Y="10">
		|</net>
	""".trimMargin()

	/** Switch basic — single switch with two outputs */
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

	/** Semaphore basic — single semaphore between two InOuts */
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

	/** Two parallel tracks — independent InOut pairs */
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

	/** Empty grid — just two InOuts in a 50x50 grid */
	val EMPTY_GRID_XML = """
		|<?xml version="1.0"?>
		|<!DOCTYPE net>
		|<net X="50" Y="50">
		|	<InOut X="10" Y="10" SpatialType="HORIZONTAL" orientation="false" name="A"/>
		|	<InOut X="20" Y="10" SpatialType="HORIZONTAL" orientation="true" name="B"/>
		|</net>
	""".trimMargin()

	private val reader = XmlContextReader()

	/**
	 * Parse XML string into a [DefaultEditingContext].
	 */
	fun parseEditingContext(xml: String): DefaultEditingContext = reader.parse(xml)

	/**
	 * Parse XML string into a [DefaultEditingContext], optionally skipping structural validation.
	 */
	fun parseEditingContext(
		xml: String,
		skipStructuralValidation: Boolean
	): DefaultEditingContext = reader.parse(xml, skipStructuralValidation)

	/**
	 * Parse XML string and create a [DefaultSimulationContext].
	 */
	fun parseSimulationContext(
		xml: String,
		processFactory: SimulationProcessFactory
	): DefaultSimulationContext {
		val editingCtx = reader.parse(xml)
		return DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
	}

	/**
	 * Create an empty simulation context (no cells, no InOuts).
	 * Equivalent to [SimulationContextFactory.createEmptyContext] but KMP-compatible.
	 */
	fun createEmptySimulationContext(
		processFactory: SimulationProcessFactory
	): DefaultSimulationContext {
		val editingCtx = DefaultEditingContext(100, 100)
		return DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
	}
}
