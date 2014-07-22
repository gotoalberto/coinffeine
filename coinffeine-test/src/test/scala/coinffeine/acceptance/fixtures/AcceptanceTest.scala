package coinffeine.acceptance.fixtures

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Second, Seconds, Span}
import org.scalatest.{GivenWhenThen, Outcome, ShouldMatchers, fixture}

import coinffeine.peer.ProtocolConstants
import coinffeine.peer.api.CoinffeineApp
import coinffeine.peer.api.CoinffeineNetwork.Connected

/** Base trait for acceptance testing that includes a test fixture */
trait AcceptanceTest extends fixture.FeatureSpec
  with GivenWhenThen
  with Eventually
  with ShouldMatchers
  with ScalaFutures {

  /** Easy to override protocol constants used during the acceptance test */
  def protocolConstants: ProtocolConstants = ProtocolConstants.Default

  override implicit def patienceConfig = PatienceConfig(
    timeout = Span(10, Seconds),
    interval = Span(1, Second)
  )

  class IntegrationTestFixture {

    private val broker = new TestBrokerComponent(protocolConstants).broker
    Await.ready(broker.start(), Duration.Inf)

    /** Loan pattern for a peer. It is guaranteed that the peers will be destroyed
      * even if the block throws exceptions.
      */
    def withPeer[T](block: CoinffeineApp => T): T = {
      val peer = buildPeer()
      try {
        block(peer)
      } finally {
        peer.close()
      }
    }

    /** Loan pattern for a couple of peers. */
    def withPeerPair[T](block: (CoinffeineApp, CoinffeineApp) => T): T =
      withPeer(bob =>
        withPeer(sam =>
          block(bob, sam)
        ))

    private[AcceptanceTest] def close(): Unit = {
      broker.close()
    }

    private def buildPeer() = new TestCoinffeineApp(broker.address, protocolConstants).app
  }

  override type FixtureParam = IntegrationTestFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val fixture = new IntegrationTestFixture()
    try {
      withFixture(test.toNoArgTest(fixture))
    } finally {
      fixture.close()
    }
  }

  protected def givenConnectedPeer(peer: CoinffeineApp): Unit = {
    peer.network.connect().futureValue should be (Connected)
  }

  protected def givenConnectedPeers(peers: CoinffeineApp*): Unit = {
    val results = Future.sequence(peers.map(_.network.connect()))
    if (!results.futureValue.forall(_ == Connected)) {
      fail("Couldn't connect the peers to the network")
    }
  }
}
