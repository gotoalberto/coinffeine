package coinffeine.acceptance.fixtures

import com.typesafe.config.{Config, ConfigFactory}

import coinffeine.common.test.DefaultTcpPortAllocator
import coinffeine.model.bitcoin.IntegrationTestNetworkComponent
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.FiatCurrency
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.{BitcoinPeerActor, MockBlockchainComponent}
import coinffeine.peer.config.ConfigComponent
import coinffeine.peer.exchange.protocol.impl.DefaultExchangeProtocol
import coinffeine.protocol.gateway.protorpc.ProtoRpcMessageGateway
import coinffeine.protocol.messages.brokerage.Market
import coinffeine.protocol.serialization.DefaultProtocolSerializationComponent
import coinffeine.server.arbiter.HandshakeArbiterActor
import coinffeine.server.broker.{BrokerActor, BrokerSupervisorActor}

/** Cake-pattern factory of brokers configured for E2E testing */
private[fixtures] class TestBrokerComponent(override val protocolConstants: ProtocolConstants)
  extends BrokerSupervisorActor.Component
  with BrokerActor.Component
  with HandshakeArbiterActor.Component
  with ProtoRpcMessageGateway.Component
  with BitcoinPeerActor.Component
  with DefaultProtocolSerializationComponent
  with DefaultExchangeProtocol.Component
  with MockBlockchainComponent
  with ProtocolConstants.Component
  with IntegrationTestNetworkComponent
  with ConfigComponent {

  private lazy val port = DefaultTcpPortAllocator.allocatePort()

  lazy val broker: TestBroker = {
    val markets: Set[Market[FiatCurrency]] = Set(Market(Euro))
    new TestBroker(brokerSupervisorProps(markets), port)
  }

  override lazy val config: Config = dynamicConfig.withFallback(ConfigFactory.load())

  private def dynamicConfig: Config = ConfigFactory.parseString(
    s"""
       | coinffeine.broker {
       |   listenAddress = "coinffeine://localhost:$port"
       |   address = "coinffeine://localhost:$port"
       | }
     """.stripMargin)
}
