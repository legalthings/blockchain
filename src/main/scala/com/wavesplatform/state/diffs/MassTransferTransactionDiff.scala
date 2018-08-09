package com.wavesplatform.state.diffs

import cats.implicits._
import com.wavesplatform.state._
import com.wavesplatform.account.Address
import com.wavesplatform.transaction.ValidationError
import com.wavesplatform.transaction.ValidationError.{GenericError, Validation}
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.transfer._

object MassTransferTransactionDiff {

  def apply(blockchain: Blockchain, blockTime: Long, height: Int)(tx: MassTransferTransaction): Either[ValidationError, Diff] = {
    def parseTransfer(xfer: ParsedTransfer): Validation[(Map[Address, Portfolio], Long)] = {
      for {
        recipientAddr <- blockchain.resolveAlias(xfer.address)
        portfolio = tx.assetId match {
          case None      => Map(recipientAddr -> Portfolio(xfer.amount, LeaseBalance.empty))
          case Some(aid) => Map(recipientAddr -> Portfolio(0, LeaseBalance.empty))
        }
      } yield (portfolio, xfer.amount)
    }
    val portfoliosEi = tx.transfers.traverse(parseTransfer)

    portfoliosEi.flatMap { list: List[(Map[Address, Portfolio], Long)] =>
      val sender   = Address.fromPublicKey(tx.sender.publicKey)
      val foldInit = (Map(sender -> Portfolio(-tx.fee, LeaseBalance.empty)), 0L)
      val (recipientPortfolios, totalAmount) = list.fold(foldInit) { (u, v) =>
        (u._1 combine v._1, u._2 + v._2)
      }
      val completePortfolio = recipientPortfolios.combine(tx.assetId match {
        case None      => Map(sender -> Portfolio(-totalAmount, LeaseBalance.empty))
        case Some(aid) => Map(sender -> Portfolio(0, LeaseBalance.empty))
      })

      val assetIssued = tx.assetId.forall(blockchain.assetDescription(_).isDefined)

      Either.cond(assetIssued, Diff(height, tx, completePortfolio), GenericError(s"Attempt to transfer a nonexistent asset"))
    }
  }
}
