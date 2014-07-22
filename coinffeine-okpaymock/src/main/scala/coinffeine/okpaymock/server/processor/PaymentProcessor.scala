package coinffeine.okpaymock.server.processor

import coinffeine.model.currency.{CurrencyAmount, FiatAmount, FiatCurrency}
import coinffeine.model.payment.Payment

/** Thread-unsafe simplified model of OKPay payment processor */
class PaymentProcessor {
  import scalaz.Scalaz._

  private var balances = Seq.empty[AccountBalance]
  private var payments = Seq.empty[Payment[_ <: FiatCurrency]]

  def createNewAccount(openingBalance: Map[FiatCurrency, FiatAmount]): Account = {
    val balance = new AccountBalance(balances.size + 1, openingBalance)
    balances :+= balance
    balance.account
  }

  def removeAccount(seedToken: String): Unit = {
    balances = balances.filterNot(_.account.seedToken == seedToken)
  }

  def retrieveWalletBalance(walletId: String, token: String): FaultOr[Seq[FiatAmount]] = for {
    account <- requireValidAccount(walletId, token)
  } yield account.balanceMap.values.toSeq

  def sendPayment[C <: FiatCurrency](token: String, walletId: String, receiverId: String,
                                     amount: CurrencyAmount[C], comment: String): FaultOr[Payment[C]] =
    for {
      source <- requireValidAccount(walletId, token)
      availableAmount <- requireAtLeast(source, amount)
      target <- requireExistingAccount(receiverId)
      _ <- requireCanAccept(target, amount.currency)
    } yield {
      val payment = source.pay(payments.size + 1, target, amount, comment)
      payments :+= payment
      payment
    }

  private def requireValidAccount(walletId: String, token: String): FaultOr[AccountBalance] = for {
    account <- requireExistingAccount(walletId)
    _ <- if (account.validToken(token)) ().success else OkPayFault.AuthenticationFailed.failure
  } yield account

  private def requireExistingAccount(walletId: String): FaultOr[AccountBalance] =
    balances.collectFirst {
      case balance if balance.account.walletId == walletId => balance
    }.toSuccess(OkPayFault.AccountNotFound)

  private def requireAtLeast(account: AccountBalance, amount: FiatAmount): FaultOr[FiatAmount] = for {
    _ <- if (account.operatesWith(amount.currency)) ().success
         else OkPayFault.CurrencyDisabled.failure
    _ <- if (account.balanceMap(amount.currency) >= amount) ().success
         else OkPayFault.NotEnoughMoney.failure
  } yield account.balanceMap(amount.currency)

  private def requireCanAccept(account: AccountBalance, currency: FiatCurrency): FaultOr[Unit] =
    if (account.operatesWith(currency)) ().success else OkPayFault.UnsupportedPaymentMethod.failure
}
