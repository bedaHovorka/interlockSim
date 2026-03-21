package cz.vutbr.fit.interlockSim.fastsim

internal object EmbeddedResources {
	val VYHYBNA_XML: String = """<?xml version="1.0"?>
<!DOCTYPE net>
<net X="100" Y="100">
  <RailSemaphore X="16" Y="8" SpatialType="HORIZONTAL" orientation="true" name="doA1"/>
  <InOut X="30" Y="8" SpatialType="HORIZONTAL" orientation="true" name="B"/>
  <RailSemaphore X="17" Y="9" SpatialType="HORIZONTAL" orientation="true" name="doA2"/>
  <RailSwitch X="26" Y="8" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_TRUE" name="vB"/>
  <InOut X="11" Y="8" SpatialType="HORIZONTAL" orientation="false" name="A"/>
  <RailSemaphore X="24" Y="9" SpatialType="HORIZONTAL" orientation="false" name="doB2"/>
  <RailSwitch X="15" Y="8" SpatialType="HORIZONTAL" Type="SIMPLE_RIGHT_FALSE" name="vA"/>
  <RailSemaphore X="14" Y="8" SpatialType="HORIZONTAL" orientation="false" name="zA"/>
  <RailSemaphore X="25" Y="8" SpatialType="HORIZONTAL" orientation="false" name="doB1"/>
  <RailSemaphore X="27" Y="8" SpatialType="HORIZONTAL" orientation="true" name="zB"/>
  <SimpleTrackBlock fromX="16" fromY="8" toX="25" toY="8" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="30" fromY="8" toX="27" toY="8" fromSegment="A" toSegment="F" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="17" fromY="9" toX="24" toY="9" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="15" fromY="8" toX="16" toY="8" fromSegment="F" toSegment="A" length="5.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="14" fromY="8" toX="15" toY="8" fromSegment="F" toSegment="A" length="5.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="24" fromY="9" toX="26" toY="8" fromSegment="F" toSegment="D" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="25" fromY="8" toX="26" toY="8" fromSegment="F" toSegment="A" length="5.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="17" fromY="9" toX="15" toY="8" fromSegment="A" toSegment="G" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="11" fromY="8" toX="14" toY="8" fromSegment="F" toSegment="A" length="100.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
  <SimpleTrackBlock fromX="27" fromY="8" toX="26" toY="8" fromSegment="A" toSegment="F" length="5.0" maxSpeedfrom="24.0" maxSpeedto="24.0"/>
</net>"""
}
