package co.topl.nodeView.nodeViewHolder

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import co.topl.nodeView.NodeViewHolder
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.{MockState, State}
import co.topl.settings.{AppContext, StartupOpts}
import co.topl.utils.CommonGenerators
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.{BeforeAndAfterAll, PrivateMethodTester}

import scala.concurrent.Await
import scala.concurrent.duration._

class NodeViewHolderSpec
    extends AnyPropSpec
    with PrivateMethodTester
    with CommonGenerators
    with MockState
    with BeforeAndAfterAll {

  type MP = MemPool

  implicit private val actorSystem: ActorSystem = ActorSystem("NodeviewHolderSpec")
  import actorSystem.dispatcher

  private var appContext: AppContext = _
  private var nvhTestRef: TestActorRef[NodeViewHolder] = _
  private var nodeView: NodeViewHolder = _
  private var state: State = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    appContext = new AppContext(settings, StartupOpts(), None)
    state = createState(inTempFile = false)
    nvhTestRef = TestActorRef(new NodeViewHolder(settings, appContext), "nvhTest")
    nodeView = nvhTestRef.underlyingActor
  }

  // TODO replace reward transactions with valid transactions
  property("Rewards transactions are removed from transactions extracted from a block being rolled back") {
    forAll(blockGen) { block =>
      val polyReward = sampleUntilNonEmpty(polyTransferGen)
      val arbitReward = sampleUntilNonEmpty(arbitTransferGen)
      val rewardBlock = block.copy(transactions = Seq(arbitReward, polyReward))

      val updateMemPool = PrivateMethod[MP](Symbol("updateMemPool"))
      val memPool = nodeView invokePrivate updateMemPool(Seq(rewardBlock), Seq(), MemPool.emptyPool, state)
      memPool.contains(polyReward) shouldBe false
      memPool.contains(arbitReward) shouldBe false
    }
  }

  override protected def afterAll(): Unit =
    Await.result(actorSystem.terminate(), 10.seconds)
}
