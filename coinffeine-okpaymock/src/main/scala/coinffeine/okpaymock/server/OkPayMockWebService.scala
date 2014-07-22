package coinffeine.okpaymock.server

import java.lang.{Boolean, Long}
import java.math.BigDecimal
import java.util.{Currency => JavaCurrency}
import javax.jws._
import javax.xml.bind.annotation.XmlSeeAlso
import javax.xml.ws.{Action, RequestWrapper, ResponseWrapper}
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout

import coinffeine.model.currency._
import coinffeine.model.payment.Payment
import coinffeine.okpaymock._
import coinffeine.okpaymock.server.PaymentProcessorActor.{RetrieveWalletBalance, SendPayment, WalletBalance}
import coinffeine.okpaymock.server.processor.OkPayFault
import coinffeine.peer.payment.okpay.OkPayWebServiceClient

@WebService(targetNamespace = "https://api.okpay.com", name = "I_OkPayAPI")
@XmlSeeAlso(Array(classOf[ObjectFactory]))
class OkPayMockWebService(ref: ActorRef) extends IOkPayAPI {

  private val factory = new ObjectFactory()
  private implicit val timeout = Timeout(5.seconds)

  @WebMethod(operationName = "Wallet_Get_Balance",
    action = "https://api.okpay.com/I_OkPayAPI/Wallet_Get_Balance")
  @Action(input = "https://api.okpay.com/I_OkPayAPI/Wallet_Get_Balance",
    output = "https://api.okpay.com/I_OkPayAPI/Wallet_Get_BalanceResponse")
  @RequestWrapper(localName = "Wallet_Get_Balance", targetNamespace = "https://api.okpay.com",
    className = "coinffeine.okpaymock.WalletGetBalance")
  @ResponseWrapper(localName = "Wallet_Get_BalanceResponse",
    targetNamespace = "https://api.okpay.com",
    className = "coinffeine.okpaymock.WalletGetBalanceResponse")
  @WebResult(name = "Wallet_Get_BalanceResult", targetNamespace = "https://api.okpay.com")
  override def walletGetBalance(
      @WebParam(name = "WalletID", targetNamespace = "https://api.okpay.com")
      walletID: String,
      @WebParam(name = "SecurityToken", targetNamespace = "https://api.okpay.com")
      securityToken: String): ArrayOfBalance = {
    val walletBalance = askFor[WalletBalance](RetrieveWalletBalance(walletID, securityToken))
    formatBalance(walletBalance.balances)
  }

  private def formatBalance(balances: Seq[FiatAmount]): ArrayOfBalance = {
    val arrayOfBalance = factory.createArrayOfBalance()
    for (fiatAmount <- balances) {
      val balance = factory.createBalance()
      balance.setCurrency(
        factory.createBalanceCurrency(fiatAmount.currency.javaCurrency.getCurrencyCode))
      balance.setAmount(fiatAmount.value.underlying())
      arrayOfBalance.getBalance.add(balance)
    }
    arrayOfBalance
  }

  @WebMethod(operationName = "Send_Money", action = "https://api.okpay.com/I_OkPayAPI/Send_Money")
  @Action(input = "https://api.okpay.com/I_OkPayAPI/Send_Money",
    output = "https://api.okpay.com/I_OkPayAPI/Send_MoneyResponse")
  @RequestWrapper(localName = "Send_Money", targetNamespace = "https://api.okpay.com",
    className = "coinffeine.okpaymock.SendMoney")
  @ResponseWrapper(localName = "Send_MoneyResponse", targetNamespace = "https://api.okpay.com",
    className = "coinffeine.okpaymock.SendMoneyResponse")
  @WebResult(name = "Send_MoneyResult", targetNamespace = "https://api.okpay.com")
  override def sendMoney(@WebParam(name = "WalletID", targetNamespace = "https://api.okpay.com")
                         walletID: String,
                         @WebParam(name = "SecurityToken",
                           targetNamespace = "https://api.okpay.com")
                         securityToken: String,
                         @WebParam(name = "Receiver", targetNamespace = "https://api.okpay.com")
                         receiver: String,
                         @WebParam(name = "Currency", targetNamespace = "https://api.okpay.com")
                         currency: String,
                         @WebParam(name = "Amount", targetNamespace = "https://api.okpay.com")
                         amount: BigDecimal,
                         @WebParam(name = "Comment", targetNamespace = "https://api.okpay.com")
                         comment: String,
                         @WebParam(name = "IsReceiverPaysFees",
                           targetNamespace = "https://api.okpay.com")
                         isReceiverPaysFees: Boolean,
                         @WebParam(name = "Invoice", targetNamespace = "https://api.okpay.com")
                         invoice: String): TransactionInfo = {
    val fiatAmount = FiatCurrency(JavaCurrency.getInstance(currency)).amount(amount)
    val message = SendPayment(securityToken, walletID, receiver, fiatAmount, comment)
    val payment = askFor[Payment[_ <: FiatAmount]](message)
    val txInfo = factory.createTransactionInfo()
    txInfo.setAmount(amount)
    txInfo.setComment(factory.createTransactionInfoComment(comment))
    txInfo.setCurrency(factory.createTransactionInfoCurrency(currency))
    txInfo.setDate(factory.createTransactionInfoDate(
      payment.date.toString(OkPayWebServiceClient.DateFormat)))
    txInfo.setID(payment.id.toLong)
    txInfo.setNet(amount)
    txInfo.setReceiver(factory.createTransactionInfoReceiver(accountInfoOf(payment.receiverId)))
    txInfo.setSender(factory.createTransactionInfoSender(accountInfoOf(payment.senderId)))
    txInfo
  }

  private def accountInfoOf(walletId: String): AccountInfo = {
    val accountInfo = factory.createAccountInfo()
    accountInfo.setWalletID(factory.createAccountInfoWalletID(walletId))
    accountInfo
  }

  private def askFor[Reply](message: Any): Reply =
    Await.result(ref ? message, timeout.duration) match {
      case exception: OkPayFault => throw exception
      case reply => reply.asInstanceOf[Reply]
    }

  override def transactionGet(walletID: String, securityToken: String, txnID: Long,
                              invoice: String): TransactionInfo = ???

  // Unimplemented methods
  override def subscriptionGet(walletID: String, securityToken: String,
                               subscriptionID: Long): Subscription = ???
  override def debitCardPrepay(walletID: String, securityToken: String, email: String,
                               currency: String, isCourierDelivery: Boolean,
                               comment: String): Long = ???
  override def walletGetCurrencyBalance(walletID: String, securityToken: String,
                                        currency: String): Balance = ???
  override def subscriptionUpdate(walletID: String, securityToken: String, sub: Subscription,
                                  comment: String): Subscription = ???
  override def getDateTime: String = ???
  override def subscriptionsFilter(walletID: String, securityToken: String, title: String,
                                   from: String, till: String,
                                   statuses: ArrayOfSubscriptionStatuses): ArrayOfSubscription = ???
  override def subscriptionGetOperations(walletID: String, securityToken: String,
                                         subscriptionID: Long): ArrayOfTransactionInfo = ???
  override def exAccountCheck(walletID: String, securityToken: String, account: String): Long = ???
  override def exClientCheckStatus(walletID: String, securityToken: String,
                                   email: String): ClientStatus = ???
  override def accountCheck(walletID: String, securityToken: String, account: String): Long = ???
  override def withdrawToEcurrencyCalculate(walletID: String, securityToken: String,
                                            paymentMethod: String, amount: BigDecimal,
                                            currency: String,
                                            feesFromAmount: Boolean): WithdrawalInfo = ???
  override def transactionHistory(walletID: String, securityToken: String, from: String,
                                  till: String, pageSize: Integer,
                                  pageNumber: Integer): HistoryInfo = ???
  override def withdrawToEcurrency(walletID: String, securityToken: String, paymentMethod: String,
                                   paySystemAccount: String, amount: BigDecimal, currency: String,
                                   feesFromAmount: Boolean, invoice: String): WithdrawalInfo = ???
}
