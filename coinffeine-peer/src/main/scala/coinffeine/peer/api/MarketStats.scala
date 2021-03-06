package coinffeine.peer.api

import scala.concurrent.Future

import coinffeine.model.currency.{CurrencyAmount, FiatCurrency}
import coinffeine.model.market.OrderBookEntry
import coinffeine.protocol.messages.brokerage.{Market, Quote}

/** Give access to current and historical prices and other market stats. */
trait MarketStats {

  /** Check current prices for a given market */
  def currentQuote[C <: FiatCurrency](market: Market[C]): Future[Quote[C]]

  /** Current open orders for a given market (anonymized order book) */
  def openOrders[C <: FiatCurrency](market: Market[C]): Future[Set[OrderBookEntry[CurrencyAmount[C]]]]
}
