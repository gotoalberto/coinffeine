package coinffeine.peer.exchange.micropayment

import scala.concurrent.Future
import scala.util.{Failure, Try}

import akka.actor._
import akka.pattern._

import coinffeine.model.currency.FiatCurrency
import coinffeine.peer.ProtocolConstants
import coinffeine.peer.exchange.micropayment.MicroPaymentChannelActor._
import coinffeine.peer.exchange.micropayment.SellerMicroPaymentChannelActor.PaymentValidationResult
import coinffeine.peer.exchange.protocol.MicroPaymentChannel.{FinalStep, IntermediateStep}
import coinffeine.peer.exchange.protocol.{ExchangeProtocol, MicroPaymentChannel}
import coinffeine.peer.exchange.util.MessageForwarding
import coinffeine.peer.payment.PaymentProcessor
import coinffeine.peer.payment.PaymentProcessor.PaymentFound
import coinffeine.protocol.gateway.MessageGateway.{ReceiveMessage, Subscribe}
import coinffeine.protocol.messages.exchange._

/** This actor implements the seller's's side of the exchange. You can find more information about
  * the algorithm at https://github.com/Coinffeine/coinffeine/wiki/Exchange-algorithm
  */
class SellerMicroPaymentChannelActor[C <: FiatCurrency](exchangeProtocol: ExchangeProtocol)
  extends Actor with ActorLogging with Stash with StepTimeout {

  import context.dispatcher

  override def postStop(): Unit = {
    cancelTimeout()
  }

  override def receive: Receive = {
    case init: StartMicroPaymentChannel[C] => new InitializedSellerExchange(init).start()
  }

  private class InitializedSellerExchange(init: StartMicroPaymentChannel[C]) {
    import init._
    import init.constants.exchangePaymentProofTimeout

    private val forwarding =
      new MessageForwarding(messageGateway, exchange, exchange.role)

    def start(): Unit = {
      log.info(s"Exchange ${exchange.id}: Exchange started")
      subscribeToMessages()
      new StepBehavior(exchangeProtocol.createMicroPaymentChannel(exchange)).start()
    }

    private def subscribeToMessages(): Unit = {
      val counterpart = exchange.role.counterpart.select(exchange.peerIds)
      messageGateway ! Subscribe {
        case ReceiveMessage(PaymentProof(exchange.`id`, _), `counterpart`) => true
        case _ => false
      }
    }

    private val handleLastOfferQueries: Receive = {
      case GetLastOffer =>
        sender ! LastOffer(None)
    }


    private class StepBehavior(channel: MicroPaymentChannel[C]) {

      def start(): Unit = {
        forwardSignatures()
        channel.currentStep match {
          case _: FinalStep =>
            finishExchange()
          case intermediateStep: IntermediateStep =>
            scheduleStepTimeouts(exchangePaymentProofTimeout)
            context.become(waitForPaymentProof(intermediateStep) orElse handleLastOfferQueries)
        }
      }

      private def forwardSignatures(): Unit = {
        forwarding.forwardToCounterpart(StepSignatures(
          exchange.id,
          channel.currentStep.value,
          channel.signCurrentTransaction
        ))
      }

      private def waitForPaymentProof(step: IntermediateStep): Receive = {
        case ReceiveMessage(PaymentProof(_, paymentId), _) =>
          cancelTimeout()
          validatePayment(step, paymentId).onComplete { tryResult =>
            self ! PaymentValidationResult(tryResult)
          }
          context.become(waitForPaymentValidation(paymentId, step) orElse handleLastOfferQueries)
        case StepSignatureTimeout =>
          val errorMsg = "Timed out waiting for the buyer to provide a valid " +
            s"payment proof ${channel.currentStep}"
          log.warning(errorMsg)
          finishWith(ExchangeFailure(TimeoutException(errorMsg)))
      }

      private def waitForPaymentValidation(paymentId: String, step: IntermediateStep): Receive = {
        case PaymentValidationResult(Failure(cause)) =>
          unstashAll()
          log.warning(s"Invalid payment proof received in ${channel.currentStep}: " +
            s"$paymentId. Reason: $cause")
          context.become(waitForPaymentProof(step) orElse handleLastOfferQueries)
        case PaymentValidationResult(_) =>
          unstashAll()
          new StepBehavior(channel.nextStep).start()
        case _ => stash()
      }

      private def finishWith(result: Any): Unit = {
        resultListeners.foreach { _ ! result }
        context.become(handleLastOfferQueries)
      }

      private def finishExchange(): Unit = {
        log.info(s"Exchange ${exchange.id}: exchange finished with success")
        finishWith(ExchangeSuccess(None))
      }
    }

    private def validatePayment(step: IntermediateStep, paymentId: String): Future[Unit] = {
      implicit val timeout = PaymentProcessor.RequestTimeout
      for {
        PaymentFound(payment) <- paymentProcessor
          .ask(PaymentProcessor.FindPayment(paymentId)).mapTo[PaymentFound[C]]
      } yield {
        require(payment.amount == exchange.amounts.stepFiatAmount,
          s"Payment $step amount does not match expected amount")
        require(payment.receiverId == exchange.participants.seller.paymentProcessorAccount,
          s"Payment $step is not being sent to the seller")
        require(payment.senderId == exchange.participants.buyer.paymentProcessorAccount,
          s"Payment $step is not coming from the buyer")
        require(payment.description == PaymentDescription(exchange.id, step),
          s"Payment $step does not have the required description")
      }
    }
  }
}

object SellerMicroPaymentChannelActor {
  private case class PaymentValidationResult(result: Try[Unit])

  trait Component { this: ProtocolConstants.Component =>
    def exchangeActorProps[C <: FiatCurrency]: Props = Props[SellerMicroPaymentChannelActor[C]]
  }
}
