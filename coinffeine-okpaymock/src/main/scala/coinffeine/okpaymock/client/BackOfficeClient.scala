package coinffeine.okpaymock.client

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.actor.{ActorRef, Address, RootActorPath}
import akka.pattern._
import akka.util.Timeout

import coinffeine.model.currency.FiatAmount
import coinffeine.okpaymock.server.OkPayMockProcessor
import coinffeine.okpaymock.server.PaymentProcessorActor._
import coinffeine.okpaymock.util.RemotingActorSystem

class BackOfficeClient(host: String, port: Int, backOfficeHost: String, backOfficePort: Int) {

  private val system = RemotingActorSystem("okpayBackOffice", host, port)
  private val backOfficeAddress =
    Address("akka.tcp", OkPayMockProcessor.SystemName, backOfficeHost, backOfficePort)
  private var processor: ActorRef = _
  private implicit val timeout = Timeout(5.seconds)

  def start(): Unit = {
    val processorPath = RootActorPath(backOfficeAddress, "/") / "user" / "processor"
    processor = awaitResult(system.actorSelection(processorPath).resolveOne())
  }

  def createAccount(balances: Seq[FiatAmount]): AccountCreated = {
    val pairs = balances.map { amount => amount.currency.toString -> amount.value}
    askFor[AccountCreated](CreateAccount(pairs))
  }

  def removeAccount(seedToken: String): Unit = {
    askFor[AccountRemoved](RemoveAccount(seedToken))
  }

  def shutdown(): Unit = {
    system.shutdown()
  }

  private def askFor[Reply: ClassTag](message: Any): Reply =
    awaitResult((processor ? message).mapTo[Reply])

  private def awaitResult[T](f: Future[T]): T =  Await.result(f, timeout.duration)
}
