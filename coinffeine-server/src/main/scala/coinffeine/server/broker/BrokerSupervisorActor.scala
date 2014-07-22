package coinffeine.server.broker

import java.net.BindException
import scala.concurrent.duration._

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor._
import com.typesafe.config.Config

import coinffeine.model.currency.FiatCurrency
import coinffeine.model.network.PeerId
import coinffeine.peer.bitcoin.BitcoinPeerActor
import coinffeine.peer.config.ConfigComponent
import coinffeine.protocol.gateway.MessageGateway.{Bind, BindingError, BoundTo}
import coinffeine.protocol.gateway.{MessageGateway, PeerConnection}
import coinffeine.protocol.messages.brokerage.Market
import coinffeine.server.broker.BrokerActor.BrokeringStart
import coinffeine.server.main.ActorSystemBootstrap

class BrokerSupervisorActor(
    markets: Set[Market[FiatCurrency]],
    gatewayProps: Props,
    bitcoinPeerProps: Props,
    brokerProps: Props,
    config: Config) extends Actor {

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10 seconds) {
      case _: BindException => Stop
      case _ => Restart
    }

  private val gateway: ActorRef = context.actorOf(gatewayProps, "gateway")
  private val bitcoinPeer: ActorRef = context.actorOf(bitcoinPeerProps, "bitcoinPeer")

  val receive: Receive = {
    case ActorSystemBootstrap.Start(args) =>
      startMessageGateway()
      context.become(supervising(sender()))
  }

  private def supervising(listener: ActorRef): Receive = {
    case BindingError(cause) =>
      listener ! ActorSystemBootstrap.StartFailure(cause)
      context.stop(self)

    case BoundTo(_) =>
      markets.foreach { market =>
        context.actorOf(brokerProps) ! BrokeringStart(market, gateway, bitcoinPeer)
      }
      listener ! ActorSystemBootstrap.Started

    case Terminated(`gateway`) =>
      context.stop(self)
  }

  private def startMessageGateway(): Unit = {
    context.watch(gateway)
    val brokerId = PeerId(config.getString("coinffeine.broker.id"))
    val listenAddress = PeerConnection.parse(config.getString("coinffeine.broker.listenAddress"))
    val brokerConnection = PeerConnection.parse(config.getString("coinffeine.broker.address"))
    gateway ! Bind(brokerId, listenAddress, brokerId, brokerConnection)
  }
}

object BrokerSupervisorActor {
  val BrokerId = PeerId("broker")

  trait Component { this: BrokerActor.Component with MessageGateway.Component
    with BitcoinPeerActor.Component with ConfigComponent =>

    def brokerSupervisorProps(markets: Set[Market[FiatCurrency]]): Props =
      Props(new BrokerSupervisorActor(markets, messageGatewayProps, bitcoinPeerProps,
        brokerActorProps, config))
  }
}
