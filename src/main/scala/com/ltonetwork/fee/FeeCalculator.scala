package com.ltonetwork.fee

import com.ltonetwork.features.BlockchainFeatures
import com.ltonetwork.features.FeatureProvider._
import com.ltonetwork.settings.{Constants, FeesSettings}
import com.ltonetwork.state._
import com.ltonetwork.transaction.ValidationError.{InsufficientFee, UnsupportedTransactionType}
import com.ltonetwork.transaction.anchor.AnchorTransaction
import com.ltonetwork.transaction.association.AssociationTransaction
import com.ltonetwork.transaction.burn.BurnTransaction
import com.ltonetwork.transaction.data.DataTransaction
import com.ltonetwork.transaction.genesis.GenesisTransaction
import com.ltonetwork.transaction.lease.{CancelLeaseTransaction, LeaseTransaction}
import com.ltonetwork.transaction.register.RegisterTransaction
import com.ltonetwork.transaction.smart.SetScriptTransaction
import com.ltonetwork.transaction.sponsorship.{CancelSponsorshipTransaction, SponsorshipTransaction, SponsorshipTransactionBase}
import com.ltonetwork.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.ltonetwork.transaction.{Transaction, ValidationError}
import com.ltonetwork.utils._

import scala.language.postfixOps
import scala.util.{Left, Right}

class FeeCalculator(settings: FeesSettings, blockchain: Blockchain) {

  private def oldFees = new OldFeeCalculator(settings, blockchain)

  private def dataTransactionBytes(tx: DataTransaction, unitSize: Int): Integer =
    if (tx.data.nonEmpty) ((tx.data.map(_.toBytes.length).sum - 1) / unitSize) + 1
    else 0

  private def feesV5(height: Int, tx: Transaction): Either[ValidationError, Long] = (tx match {
    case _: GenesisTransaction           => Right(0)
    case _: TransferTransaction          => Right(1000)
    case _: LeaseTransaction             => Right(1000)
    case _: SetScriptTransaction         => Right(5000)
    case _: CancelLeaseTransaction       => Right(1000)
    case tx: MassTransferTransaction     => Right(1000 + tx.transfers.size * 100)
    case tx: AnchorTransaction           => Right(250 + tx.anchors.size * 100)
    case _: AssociationTransaction       => Right(500)
    case _: SponsorshipTransaction       => Right(5000)
    case _: CancelSponsorshipTransaction => Right(1000)
    case tx: DataTransaction             => Right(500 + dataTransactionBytes(tx, 256) * 100)
    case tx: RegisterTransaction         => Right(250 + tx.accounts.size * 100)
    case _: BurnTransaction              => Right(1000)
    case _                               => Left(UnsupportedTransactionType)
  }).map(_ * blockchain.feePrice(height))

  private def feesV4(tx: Transaction): Either[ValidationError, Long] = tx match {
    case _: GenesisTransaction           => Right(0)
    case _: TransferTransaction          => Right(0.01 lto)
    case _: LeaseTransaction             => Right(0.01 lto)
    case _: SetScriptTransaction         => Right(0.1 lto)
    case _: CancelLeaseTransaction       => Right(0.01 lto)
    case tx: MassTransferTransaction     => Right((0.01 lto) + tx.transfers.size * (0.001 lto))
    case tx: AnchorTransaction           => Right((0.01 lto) + tx.anchors.size * (0.001 lto))
    case _: AssociationTransaction       => Right(0.01 lto)
    case _: SponsorshipTransaction       => Right(0.1 lto)
    case _: CancelSponsorshipTransaction => Right(0.1 lto)
    case tx: DataTransaction             => Right((0.01 lto) + dataTransactionBytes(tx, 1024*256) * (0.001 lto))
    case tx: RegisterTransaction         => Right((0.01 lto) + tx.accounts.size * (0.001 lto))
    case _                               => Left(UnsupportedTransactionType)
  }

  private def feesV3(tx: Transaction): Either[ValidationError, Long] = tx match {
    case _: GenesisTransaction         => Right(0)
    case _: TransferTransaction        => Right(1 lto)
    case _: LeaseTransaction           => Right(1 lto)
    case _: SetScriptTransaction       => Right(1 lto)
    case _: CancelLeaseTransaction     => Right(1 lto)
    case tx: MassTransferTransaction   => Right((1 lto) + tx.transfers.size * (0.1 lto))
    case _: AnchorTransaction          => Right(0.35 lto)
    case tx: DataTransaction           => Right((1 lto) + dataTransactionBytes(tx, 1024*256) * (0.1 lto))
    case _: AssociationTransaction     => Right(1 lto)
    case _: SponsorshipTransactionBase => Right(5 lto)
    case _                             => Left(UnsupportedTransactionType)
  }

  private def feesV2(tx: Transaction): Either[ValidationError, Long] = tx match {
    case _: AnchorTransaction          => Right(0.1 lto)
    case tx: DataTransaction           => Right((0.1 lto) + dataTransactionBytes(tx, 1024*256) * (0.01 lto))
    case _                             => feesV3(tx)
  }

  private def feesV1(tx: Transaction): Either[ValidationError, Long] = tx match {
    case _: GenesisTransaction       => Right(0)
    case _: TransferTransaction      => Right(0.001 lto)
    case tx: MassTransferTransaction => Right((0.001 lto) + tx.transfers.size * (0.0005 lto))
    case _: LeaseTransaction         => Right(0.001 lto)
    case _: CancelLeaseTransaction   => Right(0.001 lto)
    case tx: DataTransaction         => Right((0.001 lto) + ((tx.bodyBytes().length - 1) / 1024) * (0.001 lto))
    case tx: AnchorTransaction       => Right((0.001 lto) + ((tx.bodyBytes().length - 1) / 1024) * (0.001 lto))
    case _: SetScriptTransaction     => Right(0.001 lto)
    case _                           => Left(UnsupportedTransactionType)
  }

  def fee(tx: Transaction): Long = fee(blockchain.height, tx)
  def minFee(tx: Transaction): Either[ValidationError, Long] = minFee(blockchain.height, tx)
  def enoughFee[T <: Transaction](tx: T): Either[ValidationError, T] = enoughFee(blockchain.height, tx)

  def fee(height: Int, tx: Transaction): Long =
    if (blockchain.isFeatureActivated(BlockchainFeatures.Juicy, height))
      feesV5(height, tx).getOrElse(tx.fee)
    else
      tx.fee

  def minFee(height: Int, tx: Transaction): Either[ValidationError, Long] =
    if (blockchain.isFeatureActivated(BlockchainFeatures.Juicy, height))
      feesV5(height, tx)
    else
      oldFees.minFee(tx)

  def consensusMinFee(height: Int, tx: Transaction): Either[ValidationError, Long] =
    if (blockchain.isFeatureActivated(BlockchainFeatures.Juicy, height))
      feesV5(height, tx)
    else if (blockchain.isFeatureActivated(BlockchainFeatures.Cobalt, height))
      feesV4(tx)
    else if (blockchain.isFeatureActivated(BlockchainFeatures.BurnFeeture, height))
      feesV3(tx)
    else if (blockchain.isFeatureActivated(BlockchainFeatures.SmartAccounts, height))
      feesV2(tx)
    else
      feesV1(tx)

  def enoughFee[T <: Transaction](height: Int, tx: T): Either[ValidationError, T] = {
    for {
      minTxFee <- minFee(height, tx)
      _ <- Either.cond(tx.fee >= minTxFee, (), insufficientFee(tx, minTxFee))
    } yield tx
  }

  private def insufficientFee[T <: Transaction](tx: T, minTxFee: Long): InsufficientFee = {
    val txName     = Constants.TransactionNames.getOrElse(tx.typeId, "unknown")
    val txFeeValue = tx.fee

    InsufficientFee(s"Fee for ${txName} transaction ($txFeeValue) does not exceed minimal value of $minTxFee")
  }
}

object FeeCalculator {
  def apply(feesSettings: FeesSettings, blockchain: Blockchain): FeeCalculator =
    new FeeCalculator(feesSettings, blockchain)
  def apply(blockchain: Blockchain): FeeCalculator = new FeeCalculator(FeesSettings.empty, blockchain)
}