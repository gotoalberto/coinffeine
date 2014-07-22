package coinffeine.okpaymock.util

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object RemotingActorSystem {

  /** Creates an actor system with remoting enabled.
    *
    * @param name  Actor system name
    * @param host  Hostname. This address should be visible from the others systems we want to
    *              interact to
    * @param port  A free TCP port
    * @return      The configured actor system
    */
  def apply(name: String, host: String, port: Int): ActorSystem = {
    val config = ConfigFactory.parseString(s"""
      | akka.remote.netty.tcp {
      |   hostname = "$host"
      |   port = $port
      | }
    """.stripMargin).withFallback(ConfigFactory.load())
    ActorSystem(name, config)
  }
}
