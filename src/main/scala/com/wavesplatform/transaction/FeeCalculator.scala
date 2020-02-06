package com.wavesplatform.transaction

import com.wavesplatform.settings.{FeesSettings, FunctionalitySettings}
import com.wavesplatform.state._
import com.wavesplatform.transaction.ValidationError.GenericError
import com.wavesplatform.transaction.transfer._

class FeeCalculator(settings: FeesSettings, blockchain: Blockchain) {

  private val Kb = 1024

  val map: Map[String, Long] = {
    settings.fees.flatMap { fs =>
      val transactionType = fs._1
      fs._2.filter(v => v.asset.toUpperCase() == "LTO").map { v =>
        transactionType.toString -> v.fee
      }
    }
  }

  def enoughFee[T <: Transaction](tx: T, blockchain: Blockchain, fs: FunctionalitySettings): Either[ValidationError, T] = enoughFee(tx)

  def enoughFee[T <: Transaction](tx: T): Either[ValidationError, T] = {
    val txFeeValue    = tx.fee
    val txAssetFeeKey = tx.builder.typeId.toString
    for {
      txMinBaseFee <- Either.cond(map.contains(txAssetFeeKey), map(txAssetFeeKey), GenericError(s"Minimum fee is not defined for $txAssetFeeKey"))
      minTxFee = minFeeFor(tx, txMinBaseFee)
      _ <- Either.cond(
        txFeeValue >= minTxFee,
        (),
        GenericError {
          s"Fee in LTO for ${tx.builder.classTag} transaction($txFeeValue) does not exceed minimal value of $minTxFee"
        }
      )
    } yield tx
  }

  private def minFeeFor(tx: Transaction, txMinBaseFee: Long): Long = tx match {
    case tx: DataTransaction =>
      val sizeInKb = 1 + (tx.bytes().length - 1) / Kb
      txMinBaseFee * sizeInKb
    case tx: MassTransferTransaction =>
      val transferFeeSpec =
        map.getOrElse(TransferTransactionV1.typeId.toString, throw new IllegalStateException("Can't find spec for TransferTransaction"))
      transferFeeSpec + txMinBaseFee * tx.transfers.size
    case _ => txMinBaseFee
  }
}

object FeeCalculator {

  private case class TransactionAssetFee(txType: Int, assetId: Option[AssetId]) {
    val key = s"TransactionAssetFee($txType, ${assetId.map(_.base58)})"
  }

}
