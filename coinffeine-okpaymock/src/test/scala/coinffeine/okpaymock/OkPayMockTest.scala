package coinffeine.okpaymock

import coinffeine.model.currency.Currency.{Euro, UsDollar}
import coinffeine.model.currency.Implicits._
import coinffeine.model.payment.Payment

class OkPayMockTest extends SoapIntegrationTest {

  "OKPay mock" should "return an error when the account doesn't exist" in { f =>
    f.nonExistingClient.currentBalance(Euro) should failWithFaultString("Account_Not_Found")
  }

  it should "return the initial wallet balance" in { f =>
    f.withExistingClient(balances = Seq(10.EUR, 20.USD)) { client =>
      client.currentBalance(Euro).futureValue should be (10.EUR)
      client.currentBalance(UsDollar).futureValue should be (20.USD)
    }
  }

  it should "send a payment between clients" in { f =>
    f.withExistingClient(balances = Seq(10.EUR)) { sender =>
      f.withExistingClient() { receiver =>
        val amount = 5.EUR
        inside(sender.sendPayment(receiver.accountId, amount, "my payment").futureValue) {
          case Payment(_, sender.`accountId`, receiver.`accountId`, `amount`, _, "my payment") =>
        }
        sender.currentBalance(Euro).futureValue should be (5.EUR)
        receiver.currentBalance(Euro).futureValue should be (5.EUR)
      }
    }
  }
}
