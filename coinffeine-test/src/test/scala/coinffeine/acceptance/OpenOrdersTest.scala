package coinffeine.acceptance

import scala.concurrent.duration._

import org.scalatest.time.{Seconds, Span}

import coinffeine.acceptance.fixtures.AcceptanceTest
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.Implicits._
import coinffeine.model.market.OrderBookEntry
import coinffeine.peer.ProtocolConstants
import coinffeine.protocol.messages.brokerage.Market

class OpenOrdersTest extends AcceptanceTest {

  val timeout = Span(3, Seconds)
  val market = Market(Euro)

  override val protocolConstants = ProtocolConstants.Default.copy(
    orderExpirationInterval = 2.seconds,
    orderResubmitInterval = 1.second
  )

  feature("A peer should manage its orders") {

    scenario("orders are resent while not matched") { f =>
      f.withPeer { peer =>
        Given("the peer is connected and have some orders opened")
        givenConnectedPeer(peer)
        val order = peer.network.submitSellOrder(0.1.BTC, 100.EUR)

        When("more than order timeout time has passed")
        Thread.sleep((peer.protocolConstants.orderExpirationInterval * 2).toMillis)

        Then("orders are have not been discarded")
        peer.marketStats.currentQuote(market).futureValue.spread should be (None -> Some(100.EUR))
        peer.marketStats.openOrders(market).futureValue should contain (OrderBookEntry(order))
      }
    }

    scenario("orders get cancelled") { f =>
      f.withPeer { peer =>
        Given("the peer is connected and have some orders opened")
        givenConnectedPeer(peer)
        peer.network.submitSellOrder(0.1.BTC, 100.EUR)
        val orderToCancel = peer.network.submitSellOrder(0.1.BTC, 120.EUR)
        eventually {
          peer.marketStats.openOrders(market).futureValue should contain (
            OrderBookEntry(orderToCancel))
        }

        When("some order is cancelled")
        peer.network.cancelOrder(orderToCancel.id)

        Then("the order gets removed from the order book")
        eventually {
          peer.marketStats.currentQuote(market).futureValue.spread should be (None -> Some(100.EUR))
        }
      }
    }
  }
}
