package coinffeine.server.broker

import scala.concurrent.duration.Duration

import akka.actor._

import coinffeine.model.currency.{CurrencyAmount, FiatAmount, FiatCurrency}
import coinffeine.model.exchange.ExchangeId
import coinffeine.model.market._
import coinffeine.model.network.PeerId
import coinffeine.peer.ProtocolConstants
import coinffeine.protocol.gateway.MessageGateway._
import coinffeine.protocol.messages.brokerage._
import coinffeine.server.arbiter.HandshakeArbiterActor
import coinffeine.server.arbiter.HandshakeArbiterActor.HandshakeStart
import coinffeine.server.broker.BrokerActor.BrokeringStart

/** A broker actor maintains the order book of BTC trading on a given market. */
private[broker] class BrokerActor(
    handshakeArbiterProps: Props,
    orderExpirationInterval: Duration) extends Actor with ActorLogging {

  override def receive: Receive = {
    case init: BrokeringStart[FiatCurrency] => new InitializedBroker(init).startBrokering()
  }

  private class InitializedBroker[C <: FiatCurrency](init: BrokeringStart[C]) {
    import init._
    private var book = OrderBook.empty(market.currency)
    private val orderTimeouts = new ExpirationSchedule[PeerId]
    private var lastPrice: Option[FiatAmount] = None

    def startBrokering(): Unit = {
      subscribeToMessages()
      context.become(processMessage.andThen(_ => scheduleNextExpiration()))
    }

    private def subscribeToMessages(): Unit = gateway ! Subscribe {
      case ReceiveMessage(PeerOrderRequests(`market`, _), _) |
           ReceiveMessage(QuoteRequest(`market`), _) |
           ReceiveMessage(OpenOrdersRequest(`market`), _) => true
      case _ => false
    }

    private def processMessage: Receive = {

      case ReceiveMessage(orders: PeerOrderRequests[C], requester: PeerId) =>
        orderTimeouts.setExpirationFor(requester, orderExpirationInterval)
        log.info(s"Updating orders of $requester")
        log.debug(s"Orders for $requester: $orders")
        updateUserOrders(orders, requester)
        clearMarket()

      case ReceiveMessage(QuoteRequest(_), requester) =>
        gateway ! ForwardMessage(Quote(market, book.spread, lastPrice), requester)

      case ReceiveMessage(OpenOrdersRequest(_), requester) =>
        val openOrders = OpenOrders(PeerOrderRequests(market, book.anonymizedEntries))
        gateway ! ForwardMessage(openOrders, requester)

      case ReceiveTimeout => expireOrders()
    }

    private def updateUserOrders(orders: PeerOrderRequests[C], peerId: PeerId): Unit = {
      val orderIds = orders.entries.map(_.id).toSet
      addOrUpdateOrders(orders, peerId)
      removeMissingUserPositions(orderIds, peerId)
    }

    private def addOrUpdateOrders(orders: PeerOrderRequests[C], peerId: PeerId): Unit = {
      book = orders.entries.foldLeft(book) { (book, order) =>
        val posId = PositionId(peerId, order.id)
        book.get(posId).fold(addNewPosition(book, order, posId)) { position =>
          updatePosition(book, order, position)
        }
      }
    }

    private def addNewPosition(book: OrderBook[C], entry: OrderBookEntry[CurrencyAmount[C]],
                               positionId: PositionId): OrderBook[C] = {
      book.addPosition(Position(entry.orderType, entry.amount, entry.price, positionId))
    }

    private def updatePosition(book: OrderBook[C], entry: OrderBookEntry[CurrencyAmount[C]],
                               position: Position[_, C]): OrderBook[C] =
      if (position.price != entry.price || position.orderType != entry.orderType) {
        log.warning(s"Discarding $entry since it is changing parameters other than amount")
        book
      } else if (entry.amount > position.amount) {
        log.warning(s"Discarding $entry since it has increased amount")
        book
      } else if (entry.amount < position.amount) {
        book.decreaseAmount(position.id, position.amount - entry.amount)
      } else {
        book // Order changes nothing
      }

    private def removeMissingUserPositions(orderIds: Set[OrderId], peerId: PeerId): Unit = {
      val existingPositions = book.userPositions(peerId).map(_.id).toSet
      val positionsToRemove = existingPositions diff orderIds.map(PositionId(peerId, _))
      book = book.cancelPositions(positionsToRemove.toSeq)
    }

    private def clearMarket(): Unit = {
      val crosses = book.crosses
      if (crosses.nonEmpty) {
        book = book.clearMarket
        log.debug(s"The following crosses were detected: $crosses")
        startArbiters(crosses)
        lastPrice = Some(crosses.last.price)
      }
    }

    private def startArbiters(crosses: Seq[OrderBook.Cross]): Unit = {
      crosses.foreach { cross =>
        val start = HandshakeStart(
          exchangeId = ExchangeId.random(),
          amount = cross.amount,
          price = cross.price,
          orders = cross.positions.map(_.orderId),
          peers = cross.positions.map(_.peerId),
          gateway,
          bitcoinPeer
        )
        log.debug(s"Launching handshake arbitrer for cross $cross")
        context.actorOf(handshakeArbiterProps) ! start
      }
    }

    private def scheduleNextExpiration(): Unit =
      context.setReceiveTimeout(orderTimeouts.timeToNextExpiration())

    private def expireOrders(): Unit = {
      val expired = orderTimeouts.removeExpired()
      book = expired.foldLeft(book)(_.cancelAllPositions(_))
      log.info("Expiring orders of " + expired.mkString(", "))
    }
  }
}

object BrokerActor {

  /** Start brokering exchanges on a given currency. */
  case class BrokeringStart[C <: FiatCurrency](
      market: Market[C], gateway: ActorRef, bitcoinPeer: ActorRef)

  trait Component { this: HandshakeArbiterActor.Component with ProtocolConstants.Component =>

    lazy val brokerActorProps: Props =
      Props(new BrokerActor(handshakeArbiterProps, protocolConstants.orderExpirationInterval))
  }
}
