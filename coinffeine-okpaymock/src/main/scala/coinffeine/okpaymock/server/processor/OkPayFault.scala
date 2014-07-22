package coinffeine.okpaymock.server.processor

/** Represents SOAP faults as produced by the OKPay service */
class OkPayFault private (exceptionId: String) extends Exception(exceptionId)

object OkPayFault {
  object AccountNotFound extends OkPayFault("Account_Not_Found")
  object AuthenticationFailed extends OkPayFault("Authentication_Failed")
  object CurrencyDisabled extends OkPayFault("Currency_Disabled")
  object NotEnoughMoney extends OkPayFault("Not_Enough_Money")
  object UnsupportedPaymentMethod extends OkPayFault("Payment_Method_Not_Supported")
  object ReceiverNotFound extends OkPayFault("Receiver_Not_Found")
}
