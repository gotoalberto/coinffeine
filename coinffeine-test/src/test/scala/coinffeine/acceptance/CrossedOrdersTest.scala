package coinffeine.acceptance

import scala.concurrent.duration._

import org.scalatest.time.{Seconds, Span}

import coinffeine.acceptance.fixtures.AcceptanceTest
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.Implicits._
import coinffeine.peer.ProtocolConstants
import coinffeine.protocol.messages.brokerage.{Market, Quote}

class CrossedOrdersTest extends AcceptanceTest {

  val timeout = Span(3, Seconds)
  val market = Market(Euro)

  override val protocolConstants = ProtocolConstants.Default.copy(
    orderResubmitInterval = 1.second
  )

  feature("Orders that cross should be cleared from the order book") {

    scenario("perfectly matching orders get cleared together") { f =>
      f.withPeerPair { (buyer, seller) =>
        Given("peers are connected")
        givenConnectedPeers(buyer, seller)

        When("buyer and seller have a perfectly matching order")
        buyer.network.submitBuyOrder(0.1.BTC, 200.EUR)
        seller.network.submitSellOrder(0.1.BTC, 100.EUR)

        Then("both orders are cleared together")
        eventually {
          seller.marketStats.currentQuote(market).futureValue should
            be (Quote(market, spread = None -> None, lastPrice = Some(150.EUR)))
        }
      }
    }

    ignore("partially matching orders get matched") { f =>
      f.withPeerPair { (buyer, seller) =>
        Given("peers are connected")
        givenConnectedPeers(buyer, seller)

        When("buyer is asking for less amount than seller")
        buyer.network.submitBuyOrder(0.1.BTC, 200.EUR)
        val sellerOrder = seller.network.submitSellOrder(1.BTC, 100.EUR)

        Then("buy order is completely matched and sell order is partially matched")
        eventually {
          seller.network.orders should contain (sellerOrder.copy(amount = 0.9.BTC))
          seller.marketStats.currentQuote(market).futureValue should
            be (Quote(market, spread = None -> Some(100.EUR), lastPrice = Some(150.EUR)))
        }
      }
    }
  }

  feature("Orders that cross should start an exchange between the parts") {

    ignore("perfectly matching orders get cleared together") { f =>
      f.withPeerPair { (buyer, seller) =>
        Given("peers are connected")
        givenConnectedPeers(buyer, seller)

        When("buyer and seller have a perfectly matching order")
        buyer.network.submitBuyOrder(0.1.BTC, 100.EUR)
        seller.network.submitSellOrder(0.1.BTC, 100.EUR)

        Then("both orders are cleared together")
        eventually {
          buyer.network.exchanges should have size 1
          buyer.network.exchanges.map(_.id) should be (seller.network.exchanges.map(_.id))
        }
      }
    }
  }
}
