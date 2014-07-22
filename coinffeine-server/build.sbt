import com.typesafe.sbt.SbtNativePackager.NativePackagerKeys._
import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.packager.archetypes.ServerLoader.Upstart
import sbtassembly.Plugin.AssemblyKeys._

name := "coinffeine-server"

packageArchetype.java_server

maintainer in Linux := "Coinffeine S.L. <info@coinffeine.com>"

packageSummary in Linux := "Centralized broker server"

packageDescription in Linux := "Coinffeine centralized component, runs a broker for all the peers"

serverLoading in Debian := Upstart

assemblySettings

jarName in assembly := "coinffeine-server-standalone.jar"

mainClass in assembly := Some("coinffeine.server.main.Main")

excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
  cp filter { _.data.getName.contains("jaxen") } // Exclude duplicated xmlbeans definitions
}

libraryDependencies ++= Dependencies.akka ++ Seq(
  Dependencies.jcommander
)
