package coinffeine.okpaymock

import java.net.URI
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scalaxb.Soap11Fault

import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.time.{Second, Seconds, Span}

import coinffeine.common.test.DefaultTcpPortAllocator
import coinffeine.model.currency.FiatAmount
import coinffeine.model.currency.Implicits._
import coinffeine.okpaymock.client.BackOfficeClient
import coinffeine.okpaymock.server.OkPayMockProcessor
import coinffeine.okpaymock.server.PaymentProcessorActor.AccountCreated
import coinffeine.peer.payment.okpay.{OkPayClient, OkPayWebServiceClient}

/** Base class for SOAP integration tests. */
trait SoapIntegrationTest extends fixture.FlatSpec with ShouldMatchers with Inside with ScalaFutures {

  protected val timeout = 10.seconds

  override implicit def patienceConfig =
    PatienceConfig(timeout = Span(timeout.toSeconds, Seconds), interval = Span(1, Second))

  class FixtureParam {
    val servicePort = DefaultTcpPortAllocator.allocatePort()
    val backOfficePort = DefaultTcpPortAllocator.allocatePort()
    val endpoint = new URI(s"http://localhost:$servicePort/OkPayAPI")
    val server = new OkPayMockProcessor(endpoint, backOfficePort)
    private val backOfficeClient = new BackOfficeClient(
      host = "localhost",
      port = DefaultTcpPortAllocator.allocatePort(),
      backOfficeHost = "localhost",
      backOfficePort
    )
    val nonExistingClient = new OkPayWebServiceClient("unknown", "invalidToken", Some(endpoint))

    def withExistingClient[T](balances: Seq[FiatAmount] = Seq(0.EUR))(block: OkPayClient => T): T = {
      val AccountCreated(walletId, seedToken) = backOfficeClient.createAccount(balances)
      val client = new OkPayWebServiceClient(walletId, seedToken, Some(endpoint))
      try {
        block(client)
      } finally {
        backOfficeClient.removeAccount(seedToken)
      }
    }

    def setUp(): Unit = {
      server.start()
      backOfficeClient.start()
    }

    def cleanUp(): Unit = {
      server.shutdown()
      backOfficeClient.shutdown()
    }
  }

  def withFixture(test: OneArgTest) = {
    val theFixture = new FixtureParam()
    try {
      theFixture.setUp()
      withFixture(test.toNoArgTest(theFixture))
    } finally theFixture.cleanUp()
  }

  def failWithFaultString(expectedFault: String): Matcher[Future[_]] = Matcher(f =>
    Await.ready(f, timeout).eitherValue.get match {
      case Right(unexpectedSuccess) => MatchResult(
        matches = false,
        rawFailureMessage = s"the future didn't failed, succeeded with $unexpectedSuccess",
        rawNegatedFailureMessage = "the future failed"
      )
      case Left(Soap11Fault(f @ soapenvelope11.Fault(_, actualFault, _,  _), _, _)) => MatchResult(
        matches = actualFault == expectedFault,
        rawFailureMessage = s"failed with fault '$actualFault' while expecting '$expectedFault'",
        rawNegatedFailureMessage = s"failed with $expectedFault"
      )
      case Left(unexpectedException) => MatchResult(
        matches = true,
        rawFailureMessage =
          s"expected a Soap11Fault with fault $expectedFault but failed with $unexpectedException",
        rawNegatedFailureMessage = s"failed with Soap11Fault wiht fault $expectedFault"
      )
    }
  )
}

