package coinffeine.okpaymock.server.processor

import org.joda.time.{DateTimeZone, DateTime}

import coinffeine.model.currency._
import coinffeine.model.payment.Payment
import coinffeine.peer.payment.okpay.TokenGenerator

private[processor] class AccountBalance(val account: Account,
                                        var balanceMap: Map[FiatCurrency, FiatAmount]) {

  private val generator = new TokenGenerator(account.seedToken)

  def this(id: Int, openingBalance: Map[FiatCurrency, FiatAmount]) =
    this(Account(s"seed-$id", s"wallet-$id"), openingBalance)

  def validToken(token: String): Boolean = generator.build(now()) == token

  def operatesWith(currency: FiatCurrency): Boolean = balanceMap.contains(currency)

  def pay[C <: FiatCurrency](paymentId: Int,
                             target: AccountBalance, amount: CurrencyAmount[C],
                             comment: String): Payment[C] = {
    withdraw(amount)
    target.deposit(amount)
    Payment(paymentId.toString, account.walletId, target.account.walletId, amount, now(), comment)
  }

  def withdraw(amount: FiatAmount): Unit = { deposit(-amount) }

  def deposit(amount: FiatAmount): Unit = {
    val previous = balanceMap(amount.currency)
    balanceMap += amount.currency -> (previous + amount)
  }

  private def now() = DateTime.now(DateTimeZone.UTC)
}
