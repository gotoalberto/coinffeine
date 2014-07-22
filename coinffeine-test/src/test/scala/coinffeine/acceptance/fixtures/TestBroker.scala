package coinffeine.acceptance.fixtures

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.Props
import akka.pattern._
import akka.util.Timeout

import coinffeine.protocol.gateway.PeerConnection
import coinffeine.server.main.ActorSystemBootstrap

/** Testing façade for a Coinffeine broker */
private[fixtures] class TestBroker(supervisorProps: Props, port: Int)
    extends TestActorSystem(supervisorProps, s"broker$port") {

  val address = PeerConnection("localhost", port)

  def start(): Future[Unit] = {
    implicit val timeout = Timeout(5.seconds)
    (supervisorRef ? ActorSystemBootstrap.Start(Array("--port", port.toString))).mapTo[Unit]
  }
}
