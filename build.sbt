organization := "edu.berkeley.cs"

version := "1.0"

name := "junctions"

scalaVersion := "2.11.6"

// Provide a managed dependency on chisel if -DchiselVersion="" is supplied on the command line.
libraryDependencies ++= (Seq("chisel").map {
  dep: String => sys.props.get(dep + "Version") map { "edu.berkeley.cs" %% dep % _ }}).flatten
