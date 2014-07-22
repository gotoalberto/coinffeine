package coinffeine.server.server

import java.net.BindException

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import coinffeine.common.test.MockActor._
import coinffeine.common.test.{AkkaSpec, MockActor}
import coinffeine.model.currency.Currency.Euro
import coinffeine.protocol.gateway.MessageGateway.{Bind, BoundTo}
import coinffeine.protocol.gateway.PeerConnection
import coinffeine.protocol.messages.brokerage.Market
import coinffeine.server.broker.BrokerActor.BrokeringStart
import coinffeine.server.broker.BrokerSupervisorActor
import coinffeine.server.main.ActorSystemBootstrap

class BrokerSupervisorActorTest extends AkkaSpec {

  val gatewayProbe = TestProbe()
  val gatewayProps = MockActor.props(gatewayProbe)
  val bitcoinPeerProbe = TestProbe()
  val bitcoinPeerProps = MockActor.props(bitcoinPeerProbe)
  val brokerProbe = TestProbe()
  val brokerProps = MockActor.props(brokerProbe)

  val config = ConfigFactory.parseString(s"""
       | coinffeine {
       |   broker {
       |     listenAddress = "coinffeine://localhost:8080"
       |     address = "coinffeine://localhost:8080"
       |     id = "broker"
       |   }
       | }
     """.stripMargin)
  val server = system.actorOf(Props(new BrokerSupervisorActor(
    Set(Market(Euro)), gatewayProps, bitcoinPeerProps, brokerProps, config)))
  var gatewayRef = gatewayProbe.expectMsgClass(classOf[MockStarted]).ref
  var brokerRef: ActorRef = _

  "The server actor" should "wait for initialization" in {
    gatewayProbe.expectNoMsg()
    server ! ActorSystemBootstrap.Start(Array.empty)
  }

  it should "initialize a bitcoin peer actor" in {
    bitcoinPeerProbe.expectMsgClass(classOf[MockStarted])
  }

  it should "initialize gateway" in {
    gatewayProbe.expectMsgPF() {
      case MockReceived(_, _, Bind(_, connection, _, _)) if connection.port == 8080 =>
    }
    gatewayRef ! MockSend(server, BoundTo(PeerConnection("localhost", 8080)))
  }

  it should "initialize brokers" in {
    brokerRef = brokerProbe.expectMsgClass(classOf[MockStarted]).ref
    brokerProbe.expectMsgPF() {
      case MockReceived(_, _, BrokeringStart(Market(Euro), _, _)) =>
    }
  }

  it should "notify successful initialization" in {
    expectMsg(ActorSystemBootstrap.Started)
  }

  it should "restart brokers" in {
    brokerRef ! MockThrow(new Error("Something went wrong"))
    brokerProbe.expectMsgClass(classOf[MockStopped])
    brokerProbe.expectMsgClass(classOf[MockRestarted])
  }

  it should "stop if protobuf server crashes" in {
    gatewayRef ! MockThrow(new BindException("Something went wrong"))
    gatewayProbe.expectMsgClass(classOf[MockStopped])
    gatewayProbe.expectNoMsg()
  }
}
