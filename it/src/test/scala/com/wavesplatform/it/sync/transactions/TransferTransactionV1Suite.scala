package com.wavesplatform.it.sync.transactions

import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.it.util._
import com.wavesplatform.state.EitherExt2
import com.wavesplatform.utils.Base58
import org.scalatest.CancelAfterFailure
import play.api.libs.json._
import com.wavesplatform.account.AddressOrAlias
import com.wavesplatform.api.http.assets.SignedTransferV1Request
import com.wavesplatform.transaction.transfer._

import scala.concurrent.duration._

class TransferTransactionV1Suite extends BaseTransactionSuite with CancelAfterFailure {

  test("waves transfer changes waves balances and eff.b.") {
    val (firstBalance, firstEffBalance)   = notMiner.accountBalances(firstAddress)
    val (secondBalance, secondEffBalance) = notMiner.accountBalances(secondAddress)

    val transferId = sender.transfer(firstAddress, secondAddress, transferAmount, minFee).id

    nodes.waitForHeightAriseAndTxPresent(transferId)

    notMiner.assertBalances(firstAddress, firstBalance - transferAmount - minFee, firstEffBalance - transferAmount - minFee)
    notMiner.assertBalances(secondAddress, secondBalance + transferAmount, secondEffBalance + transferAmount)
  }

  test("invalid signed waves transfer should not be in UTX or blockchain") {
    def invalidTx(timestamp: Long = System.currentTimeMillis, fee: Long = 25000000) =
      TransferTransactionV1
        .selfSigned(sender.privateKey, AddressOrAlias.fromString(sender.address).explicitGet(), 1, timestamp, fee, Array.emptyByteArray)
        .right
        .get

    def request(tx: TransferTransactionV1): SignedTransferV1Request =
      SignedTransferV1Request(
        Base58.encode(tx.sender.publicKey),
        tx.recipient.stringRepr,
        tx.amount,
        tx.fee,
        tx.timestamp,
        tx.attachment.headOption.map(_ => Base58.encode(tx.attachment)),
        tx.signature.base58
      )

    implicit val w =
      Json.writes[SignedTransferV1Request].transform((jsobj: JsObject) => jsobj + ("type" -> JsNumber(TransferTransactionV1.typeId.toInt)))

    val (balance1, eff1) = notMiner.accountBalances(firstAddress)

    val invalidTxs = Seq(
      (invalidTx(timestamp = System.currentTimeMillis + 1.day.toMillis), "Transaction .* is from far future"),
      (invalidTx(fee = 99999), "Fee .* does not exceed minimal value")
    )

    for ((tx, diag) <- invalidTxs) {
      assertBadRequestAndResponse(sender.broadcastRequest(request(tx)), diag)
      nodes.foreach(_.ensureTxDoesntExist(tx.id().base58))
    }

    nodes.waitForHeightArise()
    notMiner.assertBalances(firstAddress, balance1, eff1)

  }

  test("can not make transfer without having enough effective balance") {
    val (secondBalance, secondEffBalance) = notMiner.accountBalances(secondAddress)

    assertBadRequest(sender.transfer(secondAddress, firstAddress, secondEffBalance, minFee))
    nodes.waitForHeightArise()

    notMiner.assertBalances(secondAddress, secondBalance, secondEffBalance)
  }

  test("can not make transfer without having enough balance") {
    val (secondBalance, secondEffBalance) = notMiner.accountBalances(secondAddress)

    assertBadRequestAndResponse(sender.transfer(secondAddress, firstAddress, secondBalance + 1.waves, minFee),
                                "Attempt to transfer unavailable funds")
    notMiner.assertBalances(secondAddress, secondBalance, secondEffBalance)
  }

}
