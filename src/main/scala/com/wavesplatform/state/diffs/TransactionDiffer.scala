package com.wavesplatform.state.diffs

import cats.kernel.Monoid
import com.wavesplatform.account.Address
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state._
import com.wavesplatform.transaction.ValidationError.UnsupportedTransactionType
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.smart.{SetScriptTransaction, Verifier}
import com.wavesplatform.transaction.transfer._

object TransactionDiffer {

  case class TransactionValidationError(cause: ValidationError, tx: Transaction) extends ValidationError

  def apply(settings: FunctionalitySettings, prevBlockTimestamp: Option[Long], currentBlockTimestamp: Long, currentBlockHeight: Int)(
      blockchain: Blockchain,
      tx: Transaction): Either[ValidationError, Diff] = {
    for {
      _ <- Verifier(blockchain, currentBlockHeight)(tx)
      _ <- CommonValidation.disallowTxFromFuture(settings, currentBlockTimestamp, tx)
      _ <- CommonValidation.disallowTxFromPast(prevBlockTimestamp, tx)
      _ <- CommonValidation.disallowBeforeActivationTime(blockchain, currentBlockHeight, tx)
      _ <- CommonValidation.disallowDuplicateIds(blockchain, settings, currentBlockHeight, tx)
      _ <- CommonValidation.disallowSendingGreaterThanBalance(blockchain, settings, currentBlockTimestamp, tx)
      _ <- CommonValidation.checkFee(blockchain, settings, currentBlockHeight, tx)
      diff <- tx match {
        case gtx: GenesisTransaction => GenesisTransactionDiff(currentBlockHeight)(gtx)
        case t: AuthorizedTransaction =>
          (t match {
            case ttx: TransferTransaction     => TransferTransactionDiff(blockchain, settings, currentBlockTimestamp, currentBlockHeight)(ttx)
            case mtx: MassTransferTransaction => MassTransferTransactionDiff(blockchain, currentBlockTimestamp, currentBlockHeight)(mtx)
            case ltx: LeaseTransaction        => LeaseTransactionsDiff.lease(blockchain, currentBlockHeight)(ltx)
            case ltx: LeaseCancelTransaction =>
              LeaseTransactionsDiff.leaseCancel(blockchain, settings, currentBlockTimestamp, currentBlockHeight)(ltx)
            case dtx: DataTransaction               => DataTransactionDiff(blockchain, currentBlockHeight)(dtx)
            case sstx: SetScriptTransaction         => SetScriptTransactionDiff(currentBlockHeight)(sstx)
            case at: AnchorTransaction              => AnchorTransactionDiff(blockchain, currentBlockHeight)(at)
            case as: AssociationTransactionBase     => AssociationTransactionDiff(currentBlockHeight)(as)
            case stx: SponsorshipTransaction        => SponsorshipTransactionDiff.sponsor(blockchain, currentBlockHeight)(stx)
            case sctx: SponsorshipCancelTransaction => SponsorshipTransactionDiff.cancel(blockchain, currentBlockHeight)(sctx)
            case _                                  => Left(UnsupportedTransactionType)
          }).map { d: Diff =>
            val feePayer: Address = blockchain
              .sponsorOf(t.sender)
              .find(a => blockchain.portfolio(a).spendableBalance >= t.fee)
              .getOrElse(t.sender.toAddress)
            Monoid.combine(d, Diff.empty.copy(portfolios = Map((feePayer -> Portfolio(-t.fee, LeaseBalance.empty)))))

          }
      }
      positiveDiff <- BalanceDiffValidation(blockchain, currentBlockHeight, settings)(diff)
    } yield positiveDiff
  }.left.map(TransactionValidationError(_, tx))
}
