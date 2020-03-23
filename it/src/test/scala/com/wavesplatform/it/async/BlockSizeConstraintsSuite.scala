package com.wavesplatform.it.async

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.it.api.AsyncHttpApi._
import com.wavesplatform.it.transactions.NodesFromDocker
import com.wavesplatform.it.{NodeConfigs, TransferSending}
import org.scalatest._

import scala.concurrent.Await.result
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class BlockSizeConstraintsSuite extends FreeSpec with Matchers with TransferSending with NodesFromDocker {
  import BlockSizeConstraintsSuite._

  override protected val nodeConfigs: Seq[Config] =
    Seq(ConfigOverrides.withFallback(NodeConfigs.randomMiner))
  private val nodeAddresses               = nodeConfigs.map(_.getString("address")).toSet
  val transfers: Seq[TransferSending.Req] = generateTransfersToRandomAddresses(maxTxsGroup, nodeAddresses)
  private val miner                       = nodes.head
  s"Block is limited by size" in result(
    for {
      _                 <- Future.sequence((0 to maxGroups).map(_ => processRequests(transfers, includeAttachment = true)))
      _                 <- miner.waitForHeight(3)
      _                 <- Future.sequence((0 to maxGroups).map(_ => processRequests(transfers, includeAttachment = true)))
      _                 <- miner.waitForHeight(4)
      blockHeaderAfter  <- miner.blockHeadersAt(3)
    } yield {
      val maxSizeInBytes = (1.1d * 1024 * 1024).toInt // including headers

      val blockSizeInBytes = blockHeaderAfter.blocksize
      blockSizeInBytes should be <= maxSizeInBytes
    },
    10.minutes
  )

}

object BlockSizeConstraintsSuite {
  private val maxTxsGroup     = 500 // More, than 1mb of block
  private val maxGroups       = 9
  private val txsInMicroBlock = 500
  private val ConfigOverrides = ConfigFactory.parseString(s"""akka.http.server {
                                                             |  parsing.max-content-length = 3737439
                                                             |  request-timeout = 60s
                                                             |}
                                                             |
                                                             |lto {
                                                             |  network.enable-peers-exchange = no
                                                             |
                                                             |  miner {
                                                             |    quorum = 0
                                                             |    minimal-block-generation-offset = 60000ms
                                                             |    micro-block-interval = 1s
                                                             |    max-transactions-in-key-block = 0
                                                             |    max-transactions-in-micro-block = $txsInMicroBlock
                                                             |  }
                                                             |
                                                             |  blockchain.custom {
                                                             |    functionality {
                                                             |      feature-check-blocks-period = 1
                                                             |      blocks-for-feature-activation = 1
                                                             |    }
                                                             |
                                                             |    store-transactions-in-state = false
                                                             |  }
                                                             |
                                                             |  features.supported = [2, 3]
                                                             |}""".stripMargin)

}
