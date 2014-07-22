package coinffeine.server.arbiter

import scala.concurrent.duration._

import akka.actor.Props
import akka.testkit.TestProbe
import org.scalatest.mock.MockitoSugar

import coinffeine.common.test.AkkaSpec
import coinffeine.model.bitcoin.test.CoinffeineUnitTestNetwork
import coinffeine.model.bitcoin.{ImmutableTransaction, MutableTransaction}
import coinffeine.model.currency.Implicits._
import coinffeine.model.exchange.{Both, ExchangeId}
import coinffeine.model.market.OrderId
import coinffeine.model.network.PeerId
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.BitcoinPeerActor.PublishTransaction
import coinffeine.peer.bitcoin.{BitcoinPeerActor, BlockchainActor}
import coinffeine.peer.exchange.protocol.MockExchangeProtocol
import coinffeine.protocol.gateway.MessageGateway._
import coinffeine.protocol.messages.PublicMessage
import coinffeine.protocol.messages.arbitration.CommitmentNotification
import coinffeine.protocol.messages.brokerage.OrderMatch
import coinffeine.protocol.messages.handshake._
import coinffeine.server.arbiter.HandshakeArbiterActor.HandshakeStart

class HandshakeArbiterActorTest extends AkkaSpec("HandshakeArbiterSystem")
  with MockitoSugar with CoinffeineUnitTestNetwork.Component {

  class WithTestArbiter(timeout: FiniteDuration = 1.minute) {
    val ordersId = Both(OrderId("abcd"), OrderId("efgh"))
    val exchangeId = ExchangeId("1234")
    val participants = Both(
      buyer = PeerId("buyer"),
      seller = PeerId("seller")
    )
    val buyerTx = MockExchangeProtocol.DummyDeposit
    val sellerTx = MockExchangeProtocol.DummyDeposit
    val constants = ProtocolConstants(commitmentAbortTimeout = timeout, refundLockTime = 10)
    val currentHeight = 90
    val lockTime = currentHeight + constants.refundLockTime
    val buyerOrderMatch =
      OrderMatch(ordersId.buyer, exchangeId, 1 BTC, 500 EUR, lockTime, participants.seller)
    val sellerOrderMatch =
      OrderMatch(ordersId.seller, exchangeId, 1 BTC, 500 EUR, lockTime, participants.buyer)

    val listener = TestProbe()
    val gateway = TestProbe()
    val bitcoinPeer = TestProbe()
    val arbiter = system.actorOf(Props(new HandshakeArbiterActor(
      exchangeProtocol = new MockExchangeProtocol(),
      constants,
      intermediateSteps = 10
    )))
    listener.watch(arbiter)

    def shouldSubscribeForMessages() = {
      listener.send(arbiter, HandshakeStart(
        exchangeId, 1.BTC, 500.EUR, ordersId, participants, gateway.ref, bitcoinPeer.ref))
      shouldAskForCurrentHeight()
      gateway.expectMsgClass(classOf[Subscribe])
    }

    def shouldAbort(reason: String): Unit = {
      val notification = ExchangeAborted(exchangeId, reason)
      gateway.expectMsgAllOf(
        ForwardMessage(notification, participants.buyer),
        ForwardMessage(notification, participants.seller)
      )
      listener.expectTerminated(arbiter)
    }

    def shouldAskForCurrentHeight(): Unit = {
      val blockchain = TestProbe()
      bitcoinPeer.expectMsg(BitcoinPeerActor.RetrieveBlockchainActor)
      bitcoinPeer.reply(BitcoinPeerActor.BlockchainActorReference(blockchain.ref))
      blockchain.expectMsg(BlockchainActor.RetrieveBlockchainHeight)
      blockchain.reply(BlockchainActor.BlockchainHeightReached(currentHeight))
    }

    def shouldNotifyOrderMatch(): Unit = {
      shouldSubscribeForMessages()
      gateway.expectMsgAllOf(
        ForwardMessage(buyerOrderMatch, participants.buyer),
        ForwardMessage(sellerOrderMatch, participants.seller)
      )
    }

    def buyerSend(message: PublicMessage): Unit = {
      gateway.send(arbiter, ReceiveMessage(message, participants.buyer))
    }

    def sellerSend(message: PublicMessage): Unit = {
      gateway.send(arbiter, ReceiveMessage(message, participants.seller))
    }
  }

  "An arbiter" must "subscribe to relevant messages" in new WithTestArbiter {
    val Subscribe(filter) = shouldSubscribeForMessages()
    val commitmentTransaction = ImmutableTransaction(new MutableTransaction(network))
    val unknownPeer = PeerId("unknownPeer")

    val relevantEntrance = ExchangeCommitment(exchangeId, commitmentTransaction)
    val otherExchange = ExchangeId("other exchange")
    filter(ReceiveMessage(relevantEntrance, participants.buyer)) should be (true)
    filter(ReceiveMessage(relevantEntrance, participants.seller)) should be (true)
    filter(ReceiveMessage(
      ExchangeCommitment(otherExchange, commitmentTransaction), participants.buyer)) should
      be (false)
    filter(ReceiveMessage(relevantEntrance, unknownPeer)) should be (false)

    filter(ReceiveMessage(ExchangeRejection(exchangeId, "reason"), participants.buyer)) should
      be (true)
    filter(ReceiveMessage(ExchangeRejection(exchangeId, "reason"), participants.seller)) should
      be (true)
    filter(ReceiveMessage(ExchangeRejection(otherExchange, "reason"), participants.seller)) should
      be (false)
    filter(ReceiveMessage(ExchangeRejection(exchangeId, "reason"), unknownPeer)) should be (false)
  }

  it must "notify the match, collect TXs, publish them and terminate" in new WithTestArbiter {
    shouldNotifyOrderMatch()
    buyerSend(ExchangeCommitment(exchangeId, buyerTx))
    sellerSend(ExchangeCommitment(exchangeId, sellerTx))
    bitcoinPeer.expectMsgAllOf(PublishTransaction(buyerTx), PublishTransaction(sellerTx))
    val notification = CommitmentNotification(exchangeId, Both(
      buyer = buyerTx.get.getHash,
      seller = sellerTx.get.getHash
    ))
    gateway.expectMsgAllOf(
      ForwardMessage(notification, participants.buyer),
      ForwardMessage(notification, participants.seller)
    )
    listener.expectTerminated(arbiter)
  }

  it must "cancel exchange if a TX is not valid" in new WithTestArbiter {
    shouldNotifyOrderMatch()
    buyerSend(ExchangeCommitment(exchangeId, MockExchangeProtocol.InvalidDeposit))
    sellerSend(ExchangeCommitment(exchangeId, buyerTx))
    shouldAbort(s"Invalid commitments: Invalid buyer deposit")
  }

  it must "cancel handshake when participant rejects it" in new WithTestArbiter {
    shouldNotifyOrderMatch()
    gateway.expectNoMsg(500 millis)
    sellerSend(ExchangeRejection(exchangeId, "Got impatient"))
    val notification = ExchangeAborted(exchangeId, "Rejected by counterpart: Got impatient")
    gateway.expectMsg(ForwardMessage(notification, participants.buyer))
    listener.expectTerminated(arbiter)
  }

  it must "cancel handshake on timeout" in new WithTestArbiter(timeout = 1 second) {
    shouldNotifyOrderMatch()
    buyerSend(ExchangeCommitment(exchangeId, buyerTx))
    gateway.expectNoMsg(100 millis)
    shouldAbort("Timeout waiting for commitments")
  }
}
