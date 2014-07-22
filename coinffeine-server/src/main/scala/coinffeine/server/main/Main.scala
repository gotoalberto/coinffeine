package coinffeine.server.main

import akka.actor.Props

import coinffeine.model.bitcoin.MainNetComponent
import coinffeine.model.currency.Currency.{Euro, UsDollar}
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.{MockBlockchainComponent, BitcoinPeerActor}
import coinffeine.peer.config.FileConfigComponent
import coinffeine.peer.exchange.protocol.impl.DefaultExchangeProtocol
import coinffeine.protocol.gateway.protorpc.ProtoRpcMessageGateway
import coinffeine.protocol.messages.brokerage.Market
import coinffeine.protocol.serialization.DefaultProtocolSerializationComponent
import coinffeine.server.arbiter.HandshakeArbiterActor
import coinffeine.server.broker.{BrokerActor, BrokerSupervisorActor}

object Main extends ActorSystemBootstrap
  with BrokerSupervisorActor.Component
  with BrokerActor.Component
  with HandshakeArbiterActor.Component
  with DefaultExchangeProtocol.Component
  with MockBlockchainComponent
  with BitcoinPeerActor.Component
  with ProtoRpcMessageGateway.Component
  with DefaultProtocolSerializationComponent
  with MainNetComponent
  with ProtocolConstants.Component
  with FileConfigComponent {

  override val protocolConstants = ProtocolConstants.Default

  /** Just a few handpicked markets at the moment */
  val markets = Set(Market(Euro), Market(UsDollar))

  override protected val supervisorProps: Props = brokerSupervisorProps(markets)
}
