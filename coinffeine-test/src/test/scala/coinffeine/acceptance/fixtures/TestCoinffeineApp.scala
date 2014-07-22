package coinffeine.acceptance.fixtures

import scala.util.Random

import com.typesafe.config.ConfigFactory

import coinffeine.common.test.DefaultTcpPortAllocator
import coinffeine.model.bitcoin.IntegrationTestNetworkComponent
import coinffeine.peer.api.impl.DefaultCoinffeineApp
import coinffeine.peer.bitcoin._
import coinffeine.peer.config.ConfigComponent
import coinffeine.peer.exchange.fake.FakeExchangeActor
import coinffeine.peer.market._
import coinffeine.peer.payment.okpay.OkPayProcessorActor
import coinffeine.peer.{CoinffeinePeerActor, ProtocolConstants}
import coinffeine.protocol.gateway.PeerConnection
import coinffeine.protocol.gateway.protorpc.ProtoRpcMessageGateway
import coinffeine.protocol.serialization.DefaultProtocolSerializationComponent

/** Cake-pattern factory of peers configured for GUI-less testing. */
private[fixtures] class TestCoinffeineApp(
     brokerAddress: PeerConnection,
     override val protocolConstants: ProtocolConstants) extends DefaultCoinffeineApp.Component
  with CoinffeinePeerActor.Component
  with MarketInfoActor.Component
  with OrderSupervisor.Component
  with OrderActor.Component
  with FakeExchangeActor.Component
  with SubmissionSupervisor.Component
  with WalletActor.Component
  with BitcoinPeerActor.Component
  with DummyWalletComponent
  with ProtoRpcMessageGateway.Component
  with OkPayProcessorActor.Component
  with DefaultProtocolSerializationComponent
  with IntegrationTestNetworkComponent
  with MockBlockchainComponent
  with ConfigComponent
  with ProtocolConstants.Component {

  override lazy val config = dynamicConfig().withFallback(ConfigFactory.load())

  private def dynamicConfig() = {
    val port = DefaultTcpPortAllocator.allocatePort()
    ConfigFactory.parseString(
      s"""
      |coinffeine {
      |  peer {
      |    port = $port
      |    id = "user-${Random.nextInt(5000)}"
      |  }
      |  broker {
      |    address = "$brokerAddress"
      |  }
      |}
    """.stripMargin)
  }
}
