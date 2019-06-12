package com.wavesplatform.it.sync.smartcontract

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.account.PrivateKeyAccount
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.transactions.NodesFromDocker
import com.wavesplatform.it.util._
import com.wavesplatform.it.{ReportingTestName, WaitForHeight2}
import com.wavesplatform.lang.v1.compiler.CompilerV1
import com.wavesplatform.lang.v1.parser.Parser
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.smart.script.v1.ScriptV1
import com.wavesplatform.transaction.transfer.TransferTransactionV2
import com.wavesplatform.utils.dummyCompilerContext
import org.scalatest.{CancelAfterFailure, FreeSpec, Matchers}
import play.api.libs.json.JsNumber

class UTXAllowance extends FreeSpec with Matchers with WaitForHeight2 with CancelAfterFailure with ReportingTestName with NodesFromDocker {
  import UTXAllowance._

  override protected def nodeConfigs: Seq[Config] = Configs

  private def nodeA = nodes.head
  private def nodeB = nodes.last

  "create two nodes with scripted accounts and check UTX" in {
    val accounts = List(nodeA, nodeB).map(i => {

      val nodeAddress = i.createAddress()
      val acc         = PrivateKeyAccount.fromSeed(i.seed(nodeAddress)).right.get

      val tx = i.transfer(i.address, nodeAddress, 10.waves, 1.waves).id
      nodes.waitForHeightAriseAndTxPresent(tx)

      val scriptText = {
        val sc = Parser(s"""true""".stripMargin).get.value
        CompilerV1(dummyCompilerContext, sc).explicitGet()._1
      }

      val script = ScriptV1(scriptText).explicitGet()
      val setScriptTransaction = SetScriptTransaction
        .selfSigned(SetScriptTransaction.supportedVersions.head, acc, Some(script), minFee, System.currentTimeMillis())
        .right
        .get

      val setScriptId = i
        .signedBroadcast(setScriptTransaction.json() + ("type" -> JsNumber(SetScriptTransaction.typeId.toInt)))
        .id

      nodes.waitForHeightAriseAndTxPresent(setScriptId)
      acc
    })

    val txA =
      TransferTransactionV2
        .selfSigned(
          version = 2,
          sender = accounts(0),
          recipient = accounts(0),
          amount = 1.waves,
          timestamp = System.currentTimeMillis(),
          feeAmount = minFee + 0.004.waves,
          attachment = Array.emptyByteArray
        )
        .right
        .get
    assertBadRequestAndMessage(
      nodeA.signedBroadcast(txA.json() + ("type" -> JsNumber(TransferTransactionV2.typeId.toInt))).id,
      "transactions from scripted accounts are denied from UTX pool"
    )

    val txB =
      TransferTransactionV2
        .selfSigned(
          version = 2,
          sender = accounts(1),
          recipient = accounts(1),
          amount = 1.waves,
          timestamp = System.currentTimeMillis(),
          feeAmount = minFee + 0.004.waves,
          attachment = Array.emptyByteArray
        )
        .right
        .get

    val txBId = nodeB.signedBroadcast(txB.json() + ("type" -> JsNumber(TransferTransactionV2.typeId.toInt))).id
    nodes.waitForHeightArise()
    nodeA.findTransactionInfo(txBId) shouldBe None
  }

}

object UTXAllowance {
  import com.wavesplatform.it.NodeConfigs._
  val Configs: Seq[Config] = Seq(
    FirstNode.withFallback(Default.head),
    SecondNode.withFallback(Default(1))
  )
  private val FirstNode = ConfigFactory.parseString(s"""
                                                         |lto {
                                                         |  utx.allow-transactions-from-smart-accounts = false
                                                         |  miner {
                                                         |      quorum = 0
                                                         |      enable = yes
                                                         |  }
                                                         |}""".stripMargin)
  private val SecondNode = ConfigFactory.parseString(s"""
                                                          |lto {
                                                          |  utx.allow-transactions-from-smart-accounts = true
                                                          |  miner {
                                                          |      enable = no
                                                          |  }
                                                          |}""".stripMargin)

}
