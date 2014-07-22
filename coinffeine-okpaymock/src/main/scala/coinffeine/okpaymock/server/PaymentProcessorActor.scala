package coinffeine.okpaymock.server

import java.util.Currency

import akka.actor.{Actor, ActorLogging, Props}

import coinffeine.model.currency.{FiatAmount, FiatCurrency}
import coinffeine.model.payment.Payment
import coinffeine.okpaymock.server.processor.PaymentProcessor

class PaymentProcessorActor extends Actor with ActorLogging {
  import coinffeine.okpaymock.server.PaymentProcessorActor._

  private val processor = new PaymentProcessor()

  override def receive: Receive = {

    case CreateAccount(balances) =>
      val newAccount = processor.createNewAccount(parseBalances(balances))
      sender() ! AccountCreated(newAccount.walletId, newAccount.seedToken)

    case RemoveAccount(seedToken) =>
      processor.removeAccount(seedToken)
      sender() ! AccountRemoved(seedToken)

    case RetrieveWalletBalance(walletId, token) =>
      val message = processor.retrieveWalletBalance(walletId, token).fold(
        fail = identity,
        succ = WalletBalance.apply
      )
      sender() ! message

    case SendPayment(token, walletId, receiverId, amount, comment) =>
      sender() ! processor.sendPayment(token, walletId, receiverId, amount, comment)
        .fold(fail = identity, succ = identity)
  }

  private def parseBalances(balances: Seq[(String, BigDecimal)]): Map[FiatCurrency, FiatAmount] =
    (for {
      (code, value) <- balances
      currency = FiatCurrency(Currency.getInstance(code))
    } yield currency -> currency.amount(value)).toMap
}

object PaymentProcessorActor {
  val props = Props(new PaymentProcessorActor)

  case class CreateAccount(balances: Seq[(String, BigDecimal)])
  case class AccountCreated(walletId: String, seedToken: String)

  case class RemoveAccount(seedToken: String)
  case class AccountRemoved(seedToken: String)

  case class RetrieveWalletBalance(walletId: String, token: String)
  case class WalletBalance(balances: Seq[FiatAmount])

  case class SendPayment(token: String, walletId: String, receiverId: String, amount: FiatAmount,
                         comment: String)
  case class PaymentSent(payment: Payment[FiatCurrency])
}
