package coinffeine.server.arbiter

import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor._
import akka.pattern._
import akka.util.Timeout

import coinffeine.model.bitcoin.ImmutableTransaction
import coinffeine.model.currency.{BitcoinAmount, FiatAmount}
import coinffeine.model.exchange.{Both, Exchange, ExchangeId, Role}
import coinffeine.model.market.OrderId
import coinffeine.model.network.PeerId
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.bitcoin.BitcoinPeerActor.PublishTransaction
import coinffeine.peer.bitcoin.{BitcoinPeerActor, BlockchainActor}
import coinffeine.peer.config.ConfigComponent
import coinffeine.peer.exchange.protocol._
import coinffeine.protocol.gateway.MessageGateway._
import coinffeine.protocol.messages.PublicMessage
import coinffeine.protocol.messages.arbitration.CommitmentNotification
import coinffeine.protocol.messages.brokerage.OrderMatch
import coinffeine.protocol.messages.handshake._
import coinffeine.server.arbiter.HandshakeArbiterActor._

/** A handshake arbiter is an actor able to mediate between buyer and seller to publish
  * commitment transactions at the same time.
  */
private[arbiter] class HandshakeArbiterActor(
    exchangeProtocol: ExchangeProtocol,
    constants: ProtocolConstants,
    intermediateSteps: Int) extends Actor with ActorLogging {

  import context.dispatcher

  private var timeout: Option[Cancellable] = None
  private var commitmentsOpt = Both.fill[Option[ImmutableTransaction]](None)

  override def postStop(): Unit = timeout.foreach(_.cancel())

  override val receive: Receive = {
    case handshakeStart: HandshakeStart =>
      implicit val timeout = Timeout(1.seconds)
      (for {
        blockchain <- (handshakeStart.bitcoinPeer ? BitcoinPeerActor.RetrieveBlockchainActor)
          .mapTo[BitcoinPeerActor.BlockchainActorReference]
        chainHeight <- (blockchain.ref ? BlockchainActor.RetrieveBlockchainHeight)
          .mapTo[BlockchainActor.BlockchainHeightReached]
      } yield chainHeight.height + constants.refundLockTime).onComplete {
        case Success(lockTime) =>
          new InitializedArbiter(handshakeStart, lockTime).start()
        case Failure(cause) =>
          log.error(cause, s"Cannot start handshake $handshakeStart")
      }
  }

  private class InitializedArbiter(handshakeStart: HandshakeStart, lockTime: Long) {

    import handshakeStart._

    val amounts = Exchange.Amounts(
      bitcoinAmount = amount,
      fiatAmount = price * amount.value,
      // TODO: replace hardcoded value with logic
      breakdown = Exchange.StepBreakdown(intermediateSteps)
    )

    def start(): Unit = {
      scheduleAbortTimeout()
      subscribeToMessages()
      notifyOrderMatch()
      context.become(waitForCommitments)
    }

    private val waitForCommitments: Receive = {

      case ReceiveMessage(ExchangeCommitment(_, tx), connection) =>
        acceptCommitment(roleOf(connection), tx)
        if (commitmentsOpt.forall(_.isDefined)) {
          publishOrAbort(commitmentsOpt.map(_.get))
        }

      case ReceiveMessage(ExchangeRejection(_, reason), connection) =>
        val counterpart = roleOf(connection).counterpart
        val notification = ExchangeAborted(exchangeId, s"Rejected by counterpart: $reason")
        gateway ! ForwardMessage(notification, counterpart.select(peers))
        self ! PoisonPill

      case AbortTimeout =>
        log.error("Exchange {}: aborting on timeout", exchangeId)
        notifyParticipants(ExchangeAborted(exchangeId, "Timeout waiting for commitments"))
        self ! PoisonPill
    }

    private def acceptCommitment(committer: Role, tx: ImmutableTransaction): Unit = {
      if (committer.select(commitmentsOpt).isDefined) logAlreadyCommitted(committer)
      else commitmentsOpt = committer.update(commitmentsOpt, Some(tx))
    }

    private def logAlreadyCommitted(committer: Role): Unit = {
      log.warning("Exchange {}: dropping TX from {} ({}) as he has already committed one",
        exchangeId, committer, committer.select(peers))
    }

    private def publishOrAbort(commitments: Both[ImmutableTransaction]): Unit = {
      exchangeProtocol.validateCommitments(commitments, amounts) match {
        case Success(_) => finishWithSuccess(commitments)
        case Failure(validationError) => abortOnInvalidCommitments(validationError)
      }
    }

    private def finishWithSuccess(commitments: Both[ImmutableTransaction]): Unit = {
      publishTransactions(commitments)
      notifyCommitment(commitments)
      self ! PoisonPill
    }

    private def abortOnInvalidCommitments(cause: Throwable): Unit = {
      log.error(cause, "Exchange {}: aborting due to invalid commitment transactions.", exchangeId)
      notifyParticipants(
        ExchangeAborted(exchangeId, s"Invalid commitments: ${cause.getMessage}"))
      self ! PoisonPill
    }

    private def publishTransactions(commitments: Both[ImmutableTransaction]): Unit =
      commitments.toSeq.foreach(commitment => bitcoinPeer ! PublishTransaction(commitment))

    private def notifyOrderMatch(): Unit = {
      val buyerNotification = OrderMatch(
        orderId = orders.buyer,
        exchangeId,
        amount,
        price,
        lockTime,
        peers.seller
      )
      val sellerNotification = OrderMatch(
        orderId = orders.seller,
        exchangeId,
        amount,
        price,
        lockTime,
        peers.buyer
      )
      notifyParticipants(Both(buyerNotification, sellerNotification))
    }

    private def notifyCommitment(commitments: Both[ImmutableTransaction]): Unit = notifyParticipants(
      CommitmentNotification(exchangeId, commitments.map(_.get.getHash)))

    private def notifyParticipants(notifications: Both[PublicMessage]): Unit = {
      peers.zip(notifications).foreach { case (peer, notification) =>
        log.debug(s"Notifying handshake participants of $notification")
        gateway ! ForwardMessage(notification, peer)
      }
    }

    private def notifyParticipants(notification: PublicMessage): Unit = {
      notifyParticipants(Both(notification, notification))
    }

    private def subscribeToMessages(): Unit = {
      val id = exchangeId
      val isParticipant = peers.toSet
      gateway ! Subscribe {
        case ReceiveMessage(ExchangeCommitment(`id`, _), requester) if isParticipant(requester) =>
          true
        case ReceiveMessage(ExchangeRejection(`id`, _), requester) if isParticipant(requester) =>
          true
        case _ => false
      }
    }

    private def roleOf(peer: PeerId): Role = Role.of(peers, peer).getOrElse(
      throw new NoSuchElementException(s"Neither buyer or seller is associated with $peer"))
  }

  private def scheduleAbortTimeout(): Unit = {
    timeout = Some(context.system.scheduler.scheduleOnce(
      delay = constants.commitmentAbortTimeout,
      receiver = self,
      message = AbortTimeout
    ))
  }
}

object HandshakeArbiterActor {

  case class HandshakeStart(
      exchangeId: ExchangeId,
      amount: BitcoinAmount,
      price: FiatAmount,
      orders: Both[OrderId],
      peers: Both[PeerId],
      gateway: ActorRef,
      bitcoinPeer: ActorRef
  )

  trait Component { this: ExchangeProtocol.Component with ProtocolConstants.Component
    with ConfigComponent  =>

    lazy val handshakeArbiterProps = {
      val intermediateSteps = config.getInt("coinffeine.hardcoded.intermediateSteps")
      Props(new HandshakeArbiterActor(exchangeProtocol, protocolConstants, intermediateSteps))
    }
  }

  private case object AbortTimeout
}
