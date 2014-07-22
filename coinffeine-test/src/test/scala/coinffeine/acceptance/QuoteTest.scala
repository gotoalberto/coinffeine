package coinffeine.acceptance

import org.scalatest.time.{Seconds, Span}

import coinffeine.acceptance.fixtures.AcceptanceTest
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.Implicits._
import coinffeine.protocol.messages.brokerage.{Market, Quote}

class QuoteTest extends AcceptanceTest {

  val timeout = Span(3, Seconds)
  val market = Market(Euro)

  feature("Any peer should be able to query price quotes") {

    scenario("no previous order placed") { f =>
      f.withPeer { peer =>
        Given("that peer is connected but there is no orders placed")
        givenConnectedPeer(peer)

        When("a peer asks for the current quote on a currency")
        val quote = peer.marketStats.currentQuote(market)

        Then("he should get an empty quote")
        quote.futureValue should be(Quote.empty(market))
      }
    }

    scenario("previous bidding and betting") { f =>
      f.withPeerPair { (bob, sam) =>
        Given("Bob and Sam are connected peers")
        givenConnectedPeers(bob, sam)

        Given("that Bob has placed a bid and Sam an ask that does not cross")
        bob.network.submitBuyOrder(0.1.BTC, 50.EUR)
        sam.network.submitSellOrder(0.3.BTC, 180.EUR)

        When("Bob asks for the current quote on a currency")
        def quote = bob.marketStats.currentQuote(market)

        Then("he should get the current spread")
        eventually {
          quote.futureValue should be(Quote(market, Some(50.EUR) -> Some(180.EUR)))
        }
      }
    }
  }
}
