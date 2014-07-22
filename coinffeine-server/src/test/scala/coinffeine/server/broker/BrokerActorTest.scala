package coinffeine.server.broker

import scala.concurrent.duration._

import akka.actor._
import akka.testkit._

import coinffeine.common.test.AkkaSpec
import coinffeine.model.currency.Currency.{Euro, UsDollar}
import coinffeine.model.currency.Implicits._
import coinffeine.model.currency._
import coinffeine.model.exchange.Both
import coinffeine.model.market.{Ask, Bid, OrderBookEntry, OrderId}
import coinffeine.model.network.PeerId
import coinffeine.protocol.gateway.GatewayProbe
import coinffeine.protocol.gateway.MessageGateway._
import coinffeine.protocol.messages.brokerage._
import coinffeine.server.arbiter.HandshakeArbiterActor.HandshakeStart
import coinffeine.server.broker.BrokerActor.BrokeringStart

class BrokerActorTest extends AkkaSpec("BrokerSystem") {

  class FakeHandshakeArbiterActor(listener: ActorRef) extends Actor {
    override def receive: Receive = {
      case initMessage: HandshakeStart =>
        listener ! initMessage
        self ! PoisonPill
    }
  }

  class WithEurBroker(name: String) {
    val market = Market(Euro)
    val arbiterProbe = TestProbe()
    val gateway = new GatewayProbe()
    val bitcoinPeer = TestProbe()
    val broker = system.actorOf(Props(new BrokerActor(
      handshakeArbiterProps = Props(new FakeHandshakeArbiterActor(arbiterProbe.ref)),
      orderExpirationInterval = 1 second
    )), name)

    def shouldHaveQuote(expectedQuote: Quote[FiatCurrency]): Unit = {
      val quoteRequester = PeerId("quoteRequester")
      gateway.relayMessage(QuoteRequest(market), quoteRequester)
      gateway.expectForwarding(expectedQuote, quoteRequester)
    }

    def shouldHaveOpenOrders(expectedOpenOrders: OpenOrders[FiatCurrency]): Unit = {
      val requester = PeerId("requester")
      gateway.relayMessage(OpenOrdersRequest(market), requester)
      gateway.expectForwarding(expectedOpenOrders, requester)
    }

    def shouldSubscribe(): Subscribe = {
      broker ! BrokeringStart(market, gateway.ref, bitcoinPeer.ref)
      gateway.expectSubscription()
    }

    def shouldSpawnArbiter(): HandshakeStart = {
      val init = arbiterProbe.expectMsgClass(classOf[HandshakeStart])
      init.gateway should be (gateway.ref)
      init.bitcoinPeer should be (bitcoinPeer.ref)
      init
    }

    def relayBid[C <: FiatCurrency](
        amount: BitcoinAmount, price: CurrencyAmount[C], requester: String) = {
      val order = OrderBookEntry(OrderId("1"), Bid, amount, price)
      relayOrder(order, requester)
      order
    }

    def relayAsk[C <: FiatCurrency](
        amount: BitcoinAmount, price: CurrencyAmount[C], requester: String) = {
      val order = OrderBookEntry(OrderId("1"), Ask, amount, price)
      relayOrder(order, requester)
      order
    }

    def relayOrder[F <: FiatAmount](order: OrderBookEntry[F], requester: String): Unit = {
      gateway.relayMessage(PeerOrderRequests(market, Seq(order)), PeerId(requester))
    }
  }

  "A broker" must "subscribe himself to relevant messages" in new WithEurBroker("subscribe") {
    val Subscribe(filter) = shouldSubscribe()
    val client = PeerId("client1")
    val relevantOrders = PeerOrderRequests(market, Seq.empty)
    val irrelevantOrders = PeerOrderRequests(Market(UsDollar), Seq.empty)
    filter(ReceiveMessage(relevantOrders, PeerId("client1"))) should be (true)
    filter(ReceiveMessage(irrelevantOrders, PeerId("client1"))) should be (false)
    filter(ReceiveMessage(QuoteRequest(market), client)) should be (true)
    filter(ReceiveMessage(QuoteRequest(Market(UsDollar)), client)) should be (false)
    filter(ReceiveMessage(OpenOrdersRequest(market), client)) should be (true)
    filter(ReceiveMessage(OpenOrdersRequest(Market(UsDollar)), client)) should be (false)
  }

  it must "keep orders and notify both parts and start an arbiter when they cross" in
    new WithEurBroker("notify-crosses") {
      shouldSubscribe()
      relayBid(1.BTC, 900.EUR, "client1")
      relayBid(0.8.BTC, 950.EUR, "client2")
      relayAsk(0.6.BTC, 850.EUR, "client3")
      val notifiedOrderMatch = shouldSpawnArbiter()
      notifiedOrderMatch.amount should be (0.6.BTC)
      notifiedOrderMatch.price should be (900.EUR)
      notifiedOrderMatch.peers should be (Both(
        buyer = PeerId("client2"),
        seller = PeerId("client3")
      ))
    }

  it must "quote spreads" in new WithEurBroker("quote-spreads") {
    shouldSubscribe()
    shouldHaveQuote(Quote.empty(market))
    relayBid(1.BTC, 900.EUR, "client1")
    shouldHaveQuote(Quote(market, Some(900.EUR) -> None))
    relayAsk(0.8.BTC, 950.EUR, "client2")
    shouldHaveQuote(Quote(market, Some(900.EUR) -> Some(950.EUR)))
  }

  it must "quote last price" in new WithEurBroker("quote-last-price") {
    shouldSubscribe()
    relayBid(1.BTC, 900.EUR, "client1")
    relayAsk(1.BTC, 800.EUR, "client2")
    shouldSpawnArbiter()
    shouldHaveQuote(Quote(market, lastPrice = Some(850.EUR)))
  }

  it must "report open orders" in new WithEurBroker("report-open-orders") {
    shouldSubscribe()
    shouldHaveOpenOrders(OpenOrders.empty(market))
    val bidOrder = relayBid(1.BTC, 800.EUR, "client1")
    val askOrder = relayAsk(1.BTC, 900.EUR, "client2")
    shouldHaveOpenOrders(new OpenOrders(PeerOrderRequests.empty(market)
      .addEntry(bidOrder)
      .addEntry(askOrder)))
  }

  it must "cancel orders" in new WithEurBroker("cancel-orders") {
    shouldSubscribe()
    relayBid(1.BTC, 900.EUR, "client1")
    relayAsk(0.8.BTC, 950.EUR, "client2")
    gateway.relayMessage(PeerOrderRequests(market, Seq.empty), PeerId("client1"))
    shouldHaveQuote(Quote(market, None -> Some(950.EUR)))
  }

  it must "expire old orders" in new WithEurBroker("expire-orders") {
    shouldSubscribe()
    relayBid(1.BTC, 900.EUR, "client")
    gateway.expectNoMsg()
    shouldHaveQuote(Quote.empty(market))
  }

  it must "keep priority of orders when resubmitted" in new WithEurBroker("keep-priority") {
    shouldSubscribe()
    relayBid(1.BTC, 900.EUR, "first-bid")
    relayBid(1.BTC, 900.EUR, "second-bid")
    relayBid(1.BTC, 900.EUR, "first-bid")
    relayAsk(1.BTC, 900.EUR, "ask")
    val orderMatch = shouldSpawnArbiter()
    orderMatch.peers.buyer should equal (PeerId("first-bid"))
  }

  it must "label crosses with random identifiers" in new WithEurBroker("random-id") {
    shouldSubscribe()
    relayBid(1.BTC, 900.EUR, "buyer")
    relayAsk(1.BTC, 900.EUR, "seller")
    val id1 = shouldSpawnArbiter().exchangeId
    relayBid(1.BTC, 900.EUR, "buyer")
    relayAsk(1.BTC, 900.EUR, "seller")
    val id2 = shouldSpawnArbiter().exchangeId
    id1 should not (equal (id2))
  }
}
