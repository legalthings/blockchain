package com.ltonetwork.it.sync

import com.typesafe.config.Config
import com.ltonetwork.account.PrivateKeyAccount
import com.ltonetwork.block.{Block, SignerData}
import com.ltonetwork.consensus.FairPoSCalculator
import com.ltonetwork.consensus.nxt.NxtLikeConsensusBlockData
import com.ltonetwork.crypto
import com.ltonetwork.http.DebugMessage
import com.ltonetwork.it.api.AsyncNetworkApi.NodeAsyncNetworkApi
import com.ltonetwork.it.api.SyncHttpApi._
import com.ltonetwork.it.transactions.NodesFromDocker
import com.ltonetwork.it.{NodeConfigs, WaitForHeight2}
import com.ltonetwork.network.RawBytes
import com.ltonetwork.state._
import com.ltonetwork.utils.Base58
import org.scalatest.{CancelAfterFailure, FunSuite, Matchers}
import play.api.libs.json.{JsSuccess, Json, Reads}

import scala.util.Random

class PoSSuite extends FunSuite with Matchers with NodesFromDocker with WaitForHeight2 with CancelAfterFailure {

  val signerPK = PrivateKeyAccount.fromSeed(nodeConfigs.last.getString("account-seed")).explicitGet()

  implicit val nxtCDataReads = Reads { json =>
    val bt = (json \ "base-target").as[Long]
    val gs = (json \ "generation-signature").as[String]

    JsSuccess(NxtLikeConsensusBlockData(bt, ByteStr.decodeBase58(gs).get))
  }

  test("Node mines several blocks, integration test checks that block timestamps equal to time of appearence (+-1100ms)") {

    val height = nodes.last.height

    for (h <- height to (height + 10)) {

      val block = forgeBlock(h, signerPK)()

      nodes.waitForHeightArise()

      val newTimestamp = blockTimestamp(h + 1)

      block.timestamp shouldBe (newTimestamp +- 1100)
    }
  }

  test("Accept correct block") {

    nodes.last.close()
    val height = nodes.head.height
    val block  = forgeBlock(height, signerPK)()

    waitForBlockTime(block)

    nodes.head.printDebugMessage(DebugMessage(s"Send block for $height"))
    nodes.head.sendByNetwork(RawBytes.from(block))

    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig sameElements block.uniqueId.arr
  }

  test("Reject block with invalid delay") {
    val height = nodes.head.height
    val block  = forgeBlock(height, signerPK)(updateDelay = _ - 1000)

    waitForBlockTime(block)

    nodes.head.sendByNetwork(RawBytes.from(block))
    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig should not be block.uniqueId.arr
  }

  test("Reject block with invalid BT") {
    val height = nodes.head.height
    val block  = forgeBlock(height, signerPK)(updateBaseTarget = _ + 2)

    waitForBlockTime(block)

    nodes.head.sendByNetwork(RawBytes.from(block))

    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig should not be block.uniqueId.arr
  }

  test("Reject block with invalid generation signature") {
    val height = nodes.head.height
    val block = forgeBlock(height, signerPK)(updateGenSig = (gs: ByteStr) => {
      val arr  = gs.arr
      val init = arr.init
      Random.nextBytes(arr)
      ByteStr(init :+ arr.last)
    })

    waitForBlockTime(block)

    nodes.head.printDebugMessage(DebugMessage(s"Send invalid block for $height"))
    nodes.head.sendByNetwork(RawBytes.from(block))

    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig should not be block.uniqueId.arr
  }

  test("Reject block with invalid signature") {
    val otherNodePK = PrivateKeyAccount.fromSeed(nodeConfigs.head.getString("account-seed")).explicitGet()

    val height = nodes.head.height
    val block  = forgeBlock(height, signerPK)(updateBaseTarget = _ + 2)

    val resignedBlock =
      block
        .copy(signerData = SignerData(signerPK, ByteStr(crypto.sign(otherNodePK, block.bytes()))))

    waitForBlockTime(resignedBlock)

    nodes.head.sendByNetwork(RawBytes.from(resignedBlock))

    nodes.head.waitForHeight(height + 1)

    val newBlockSig = blockSignature(height + 1)

    newBlockSig should not be resignedBlock.uniqueId.arr
  }

  def waitForBlockTime(block: Block): Unit = {
    val timeout = block.timestamp - System.currentTimeMillis()

    if (timeout > 0) Thread.sleep(timeout)
  }

  def blockTimestamp(h: Int): Long = {
    (Json.parse(
      nodes.head
        .get(s"/blocks/at/$h")
        .getResponseBody
    ) \ "timestamp").as[Long]
  }

  def blockSignature(h: Int): Array[Byte] = {
    Base58
      .decode(
        (Json.parse(
          nodes.head
            .get(s"/blocks/at/$h")
            .getResponseBody
        ) \ "signature").as[String])
      .get
  }

  def forgeBlock(height: Int, signerPK: PrivateKeyAccount)(updateDelay: Long => Long = identity,
                                                           updateBaseTarget: Long => Long = identity,
                                                           updateGenSig: ByteStr => ByteStr = identity): Block = {

    val ggParentTS =
      if (height >= 3)
        Some(
          (Json
            .parse(nodes.head.get(s"/blocks/at/${height - 2}").getResponseBody) \ "timestamp").as[Long])
      else None

    val (lastBlockId, lastBlockTS, lastBlockCData) = blockInfo(height)

    val genSig: ByteStr =
      updateGenSig(
        ByteStr(generatorSignature(lastBlockCData.generationSignature.arr, signerPK.publicKey))
      )

    val validBlockDelay: Long = updateDelay(
      FairPoSCalculator
        .calculateDelay(
          hit(genSig.arr),
          lastBlockCData.baseTarget,
          nodes.head.accountBalances(signerPK.address)._2
        )
    )

    val bastTarget: Long = updateBaseTarget(
      FairPoSCalculator
        .calculateBaseTarget(
          10,
          height,
          lastBlockCData.baseTarget,
          lastBlockTS,
          ggParentTS,
          lastBlockTS + validBlockDelay
        )
    )

    val cData: NxtLikeConsensusBlockData = NxtLikeConsensusBlockData(bastTarget, genSig)

    Block
      .buildAndSign(
        version = 3: Byte,
        timestamp = lastBlockTS + validBlockDelay,
        reference = ByteStr(lastBlockId),
        consensusData = cData,
        transactionData = Nil,
        signer = signerPK,
        featureVotes = Set.empty
      )
      .explicitGet()
  }

  def blockInfo(height: Int): (Array[Byte], Long, NxtLikeConsensusBlockData) = {
    val lastBlock      = Json.parse(nodes.head.get(s"/blocks/at/$height").getResponseBody)
    val lastBlockId    = Base58.decode((lastBlock \ "signature").as[String]).get
    val lastBlockTS    = (lastBlock \ "timestamp").as[Long]
    val lastBlockCData = (lastBlock \ "nxt-consensus").as[NxtLikeConsensusBlockData]

    (lastBlockId, lastBlockTS, lastBlockCData)
  }

  private def generatorSignature(signature: Array[Byte], publicKey: Array[Byte]): Array[Byte] = {
    val s = new Array[Byte](crypto.DigestLength * 2)
    System.arraycopy(signature, 0, s, 0, crypto.DigestLength)
    System.arraycopy(publicKey, 0, s, crypto.DigestLength, crypto.DigestLength)
    crypto.fastHash(s)
  }

  private def hit(generatorSignature: Array[Byte]): BigInt = BigInt(1, generatorSignature.take(8).reverse)

  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.quorum(3))
      .overrideBase(
        _.raw(
          """
          |lto {
          |  miner {
          |      quorum = 1
          |  }
          |
          |  blockchain {
          |    custom {
          |      functionality {
          |        pre-activated-features = {
          |          4 = 0
          |        }
          |      }
          |    }
          |  }
          |}
        """.stripMargin
        ))
      .overrideBase(_.nonMiner)
      .withDefault(3)
      .withSpecial(_.raw("lto.miner.enable = yes"))
      .buildNonConflicting()
}
