package coinffeine.okpaymock.server

import java.net.URI
import javax.xml.ws.Endpoint

import akka.actor.ActorSystem

import coinffeine.okpaymock.util.RemotingActorSystem

class OkPayMockProcessor(endpoint: URI, backOfficePort: Int) {
  private var system: ActorSystem = _
  private var service: Endpoint = _

  def start(): Unit = {
    system = RemotingActorSystem(OkPayMockProcessor.SystemName, endpoint.getHost, backOfficePort)
    val processor = system.actorOf(PaymentProcessorActor.props, "processor")
    service = Endpoint.publish(endpoint.toString, new OkPayMockWebService(processor))
  }

  def shutdown(): Unit = {
    service.stop()
    system.shutdown()
  }
}

object OkPayMockProcessor {
  val SystemName = "okpaymock"
}
