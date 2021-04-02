package com.ltonetwork.state.diffs

import cats._
import com.ltonetwork.account.Address
import com.ltonetwork.features.FeatureProvider._
import com.ltonetwork.features.{BlockchainFeature, BlockchainFeatures}
import com.ltonetwork.settings.FunctionalitySettings
import com.ltonetwork.state._
import com.ltonetwork.transaction.ValidationError._
import com.ltonetwork.transaction._
import com.ltonetwork.transaction.lease._
import com.ltonetwork.transaction.smart.SetScriptTransaction
import com.ltonetwork.transaction.transfer._

import scala.concurrent.duration._
import scala.util.{Left, Right}

object CommonValidation {

  val MaxTimeTransactionOverBlockDiff: FiniteDuration     = 90.minutes
  val MaxTimePrevBlockOverTransactionDiff: FiniteDuration = 2.hours
  val ScriptExtraFee                                      = 100000000L

  def disallowSendingGreaterThanBalance[T <: Transaction](blockchain: Blockchain,
                                                          settings: FunctionalitySettings,
                                                          blockTime: Long,
                                                          tx: T): Either[ValidationError, T] = {
    def checkTransfer(sender: Address, amount: Long, feeAmount: Long) = {
      val amountDiff = Portfolio(-amount, LeaseBalance.empty)

      val feeDiff = Portfolio(-feeAmount, LeaseBalance.empty)

      val spendings       = Monoid.combine(amountDiff, feeDiff)
      val oldLtoBalance = blockchain.portfolio(sender).balance

      val newLtoBalance = oldLtoBalance + spendings.balance
      if (newLtoBalance < 0) {
        Left(
          GenericError(
            "Attempt to transfer unavailable funds: Transaction application leads to " +
              s"negative lto balance to (at least) temporary negative state, current balance equals $oldLtoBalance, " +
              s"spends equals ${spendings.balance}, result is $newLtoBalance"))
      } else Right(tx)
    }

    tx match {
      case ptx: PaymentTransaction if blockchain.portfolio(ptx.sender).balance < (ptx.amount + ptx.fee) =>
        Left(
          GenericError(
            "Attempt to pay unavailable funds: balance " +
              s"${blockchain.portfolio(ptx.sender).balance} is less than ${ptx.amount + ptx.fee}"))
      case ttx: TransferTransaction     => checkTransfer(ttx.sender, ttx.amount, ttx.fee)
      case mtx: MassTransferTransaction => checkTransfer(mtx.sender, mtx.transfers.map(_.amount).sum, mtx.fee)
      case _                            => Right(tx)
    }
  }
  def disallowDuplicateIds[T <: Transaction](blockchain: Blockchain,
                                             settings: FunctionalitySettings,
                                             height: Int,
                                             tx: T): Either[ValidationError, T] = tx match {
    case _: PaymentTransaction => Right(tx)
    case _                     => if (blockchain.containsTransaction(tx.id())) Left(AlreadyInTheState(tx.id(), 0)) else Right(tx)
  }

  def disallowBeforeActivationTime[T <: Transaction](blockchain: Blockchain, height: Int, tx: T): Either[ValidationError, T] = {

    def activationBarrier(b: BlockchainFeature) =
      Either.cond(
        blockchain.isFeatureActivated(b, height),
        tx,
        ValidationError.ActivationError(s"${tx.getClass.getSimpleName} transaction has not been activated yet")
      )
    def deactivationBarrier(b: BlockchainFeature) =
      Either.cond(
        !blockchain.isFeatureActivated(b, height),
        tx,
        ValidationError.ActivationError(s"${tx.getClass.getSimpleName} transaction has been deactivated")
      )

    val disabled = Left(GenericError("tx type is disabled"))

    tx match {
      case _: GenesisTransaction       => Right(tx)
      case _: TransferTransactionV1    => Right(tx)
      case _: TransferTransactionV2    => activationBarrier(BlockchainFeatures.SmartAccounts)
      case _: LeaseTransactionV1       => Right(tx)
      case _: LeaseTransactionV2       => activationBarrier(BlockchainFeatures.SmartAccounts)
      case _: LeaseCancelTransactionV1 => Right(tx)
      case _: LeaseCancelTransactionV2 => activationBarrier(BlockchainFeatures.SmartAccounts)
      case _: MassTransferTransaction  => Right(tx)
      case _: DataTransaction =>
        for {
          _ <- deactivationBarrier(BlockchainFeatures.SmartAccounts)
        } yield tx
      case _: SetScriptTransaction       => Right(tx)
      case _: AnchorTransaction          => Right(tx)
      case _: AssociationTransactionBase => activationBarrier(BlockchainFeatures.AssociationTransaction)
      case _: SponsorshipTransactionBase => activationBarrier(BlockchainFeatures.SponsorshipTransaction)
      case _: PaymentTransaction         => disabled
      case _                             => Left(GenericError("Unknown transaction must be explicitly activated"))
    }
  }

  def disallowTxFromFuture[T <: Transaction](settings: FunctionalitySettings, time: Long, tx: T): Either[ValidationError, T] = {
    if (tx.timestamp - time > MaxTimeTransactionOverBlockDiff.toMillis)
      Left(Mistiming(s"Transaction ts ${tx.timestamp} is from far future. BlockTime: $time"))
    else Right(tx)
  }

  def disallowTxFromPast[T <: Transaction](prevBlockTime: Option[Long], tx: T): Either[ValidationError, T] =
    prevBlockTime match {
      case Some(t) if (t - tx.timestamp) > MaxTimePrevBlockOverTransactionDiff.toMillis =>
        Left(Mistiming(s"Transaction ts ${tx.timestamp} is too old. Previous block time: $prevBlockTime"))
      case _ => Right(tx)
    }

  private def feeInUnitsVersion1(blockchain: Blockchain, height: Int, tx: Transaction): Either[ValidationError, Long] = tx match {
    case _: GenesisTransaction       => Right(0)
    case _: PaymentTransaction       => Right(1)
    case _: TransferTransaction      => Right(1)
    case tx: MassTransferTransaction => Right(1 + (tx.transfers.size + 1) / 2)
    case _: LeaseTransaction         => Right(1)
    case _: LeaseCancelTransaction   => Right(1)
    case tx: DataTransaction =>
      val base = if (blockchain.isFeatureActivated(BlockchainFeatures.SmartAccounts, height)) tx.bodyBytes() else tx.bytes()
      Right(1 + (base.length - 1) / 1024)
    case tx: AnchorTransaction   => Right(1 + (tx.bodyBytes().length - 1) / 1024)
    case _: SetScriptTransaction => Right(1)
    case _                       => Left(UnsupportedTransactionType)
  }

  private def feeInUnitsVersion2(tx: Transaction): Either[ValidationError, Long] = tx match {
    case _: GenesisTransaction         => Right(0)
    case _: TransferTransaction        => Right(1000)
    case _: LeaseTransaction           => Right(1000)
    case _: SetScriptTransaction       => Right(1000)
    case _: LeaseCancelTransaction     => Right(1000)
    case tx: MassTransferTransaction   => Right(1000 + tx.transfers.size * 100)
    case _: AnchorTransaction          => Right(100)
    case _: AssociationTransactionBase => Right(1000)
    case _: SponsorshipTransactionBase => Right(5000)
    case _                             => Left(UnsupportedTransactionType)
  }

  private def feeInUnitsVersion3(tx: Transaction): Either[ValidationError, Long] = tx match {
    case _: GenesisTransaction         => Right(0)
    case _: TransferTransaction        => Right(1000)
    case _: LeaseTransaction           => Right(1000)
    case _: SetScriptTransaction       => Right(1000)
    case _: LeaseCancelTransaction     => Right(1000)
    case tx: MassTransferTransaction   => Right(1000 + tx.transfers.size * 100)
    case _: AnchorTransaction          => Right(350)
    case _: AssociationTransactionBase => Right(1000)
    case _: SponsorshipTransactionBase => Right(5000)
    case _                             => Left(UnsupportedTransactionType)
  }

  private def feeInUnitsVersion4(tx: Transaction): Either[ValidationError, Long] = tx match {
    case _: GenesisTransaction           => Right(0)
    case _: TransferTransaction          => Right(10)
    case _: LeaseTransaction             => Right(10)
    case _: SetScriptTransaction         => Right(100)
    case _: LeaseCancelTransaction       => Right(10)
    case tx: MassTransferTransaction     => Right(10 + tx.transfers.size * 10)
    case tx: AnchorTransaction           => Right(10 + tx.anchors.size * 1)
    case _: AssociationTransactionBase   => Right(10)
    case _: SponsorshipTransaction       => Right(100)
    case _: SponsorshipCancelTransaction => Right(10)
    case _                               => Left(UnsupportedTransactionType)
  }

  def getMinFee(blockchain: Blockchain, fs: FunctionalitySettings, height: Int, tx: Transaction): Either[ValidationError, Long] = {

    // TODO: smart accounts were not activated, so why the check in fees V1?
    def feesV1() = {
      type FeeInfo = Long

      def hasSmartAccountScript: Boolean = tx match {
        case tx: Transaction with Authorized => blockchain.hasScript(tx.sender)
        case _                               => false
      }

      def feeAfterSmartAccounts(inputFee: FeeInfo): FeeInfo =
        if (hasSmartAccountScript) {
          inputFee + ScriptExtraFee
        } else inputFee

      feeInUnitsVersion1(blockchain, height, tx)
        .map(_ * Sponsorship.FeeUnit)
        .map(feeAfterSmartAccounts)
    }
    def feesV2() = feeInUnitsVersion2(tx).map(_ * Sponsorship.FeeUnit)
    def feesV3() = feeInUnitsVersion3(tx).map(_ * Sponsorship.FeeUnit)
    def feesV4() = feeInUnitsVersion4(tx).map(_ * Sponsorship.FeeUnit)

    if (blockchain.isFeatureActivated(BlockchainFeatures.PercentageBurn, height))
      feesV4()
    if (blockchain.isFeatureActivated(BlockchainFeatures.BurnFeeture, height))
      feesV3()
    else if (blockchain.isFeatureActivated(BlockchainFeatures.SmartAccounts, height))
      feesV2()
    else feesV1()
  }

  def checkFee(blockchain: Blockchain, fs: FunctionalitySettings, height: Int, tx: Transaction): Either[ValidationError, Unit] = {
    def feesV1() = {
      def restFee(inputFee: Long): Either[ValidationError, (Option[AssetId], Long)] = {
        val feeAmount = inputFee
        for {
          feeInUnits <- feeInUnitsVersion1(blockchain, height, tx)
          minimumFee    = feeInUnits * Sponsorship.FeeUnit
          restFeeAmount = feeAmount - minimumFee
          _ <- Either.cond(
            restFeeAmount >= 0,
            (),
            GenericError(s"Fee in LTO for ${tx.builder.classTag} does not exceed minimal value of $minimumFee LTOs: $feeAmount")
          )
        } yield (None, restFeeAmount)
      }

      def hasSmartAccountScript: Boolean = tx match {
        case tx: Transaction with Authorized => blockchain.hasScript(tx.sender)
        case _                               => false
      }

      def restFeeAfterSmartAccounts(inputFee: (Option[AssetId], Long)): Either[ValidationError, (Option[AssetId], Long)] =
        if (hasSmartAccountScript) {
          val (feeAssetId, feeAmount) = inputFee
          for {
            _ <- Either.cond(feeAssetId.isEmpty, (), GenericError("Transactions from scripted accounts require LTO as fee"))
            _ <- Either.cond(
              feeAmount >= 0,
              (),
              InsufficientFee()
            )
          } yield (feeAssetId, feeAmount)
        } else Right(inputFee)

      restFee(tx.fee)
        .flatMap(restFeeAfterSmartAccounts)
        .map(_ => ())
    }

    def feesV2() =
      feeInUnitsVersion2(tx)
        .map(_ * Sponsorship.FeeUnit)
        .flatMap(minFee => Either.cond(tx.fee >= minFee, (), InsufficientFee(s"Not enough fee, actual: ${tx.fee} required: $minFee")))

    def feesV3() =
      feeInUnitsVersion3(tx)
        .map(_ * Sponsorship.FeeUnit)
        .flatMap(minFee => Either.cond(tx.fee >= minFee, (), InsufficientFee(s"Not enough fee, actual: ${tx.fee} required: $minFee")))

    def feesV4() =
      feeInUnitsVersion4(tx)
        .map(_ * Sponsorship.FeeUnit)
        .flatMap(minFee => Either.cond(tx.fee >= minFee, (), InsufficientFee(s"Not enough fee, actual: ${tx.fee} required: $minFee")))

    if (blockchain.isFeatureActivated(BlockchainFeatures.PercentageBurn, height))
      feesV4()
    if (blockchain.isFeatureActivated(BlockchainFeatures.BurnFeeture, height))
      feesV3()
    else if (blockchain.isFeatureActivated(BlockchainFeatures.SmartAccounts, height))
      feesV2()
    else feesV1()
  }

}
