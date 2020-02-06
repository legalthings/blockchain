package com.wavesplatform.state.diffs

import cats._
import cats.implicits._
import com.wavesplatform.account.Address
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state._
import com.wavesplatform.transaction.ValidationError
import com.wavesplatform.transaction.ValidationError.GenericError
import com.wavesplatform.transaction.lease._

import scala.util.{Left, Right}

object LeaseTransactionsDiff {

  def lease(blockchain: Blockchain, height: Int)(tx: LeaseTransaction): Either[ValidationError, Diff] = {
    val sender    = Address.fromPublicKey(tx.sender.publicKey)
    val recipient = tx.recipient.asInstanceOf[Address]
    if (recipient == sender)
      Left(GenericError("Cannot lease to self"))
    else {
      val ap = blockchain.portfolio(tx.sender)
      if (ap.balance - ap.lease.out < tx.amount) {
        Left(GenericError(s"Cannot lease more than own: Balance:${ap.balance}, already leased: ${ap.lease.out}"))
      } else {
        val portfolioDiff: Map[Address, Portfolio] = Map(
          sender    -> Portfolio(0, LeaseBalance(0, tx.amount)),
          recipient -> Portfolio(0, LeaseBalance(tx.amount, 0))
        )
        Right(Diff(height = height, tx = tx, portfolios = portfolioDiff, leaseState = Map(tx.id() -> true)))
      }
    }
  }

  def leaseCancel(blockchain: Blockchain, settings: FunctionalitySettings, time: Long, height: Int)(
      tx: LeaseCancelTransaction): Either[ValidationError, Diff] = {
    val leaseEi = blockchain.leaseDetails(tx.leaseId) match {
      case None    => Left(GenericError(s"Related LeaseTransaction not found"))
      case Some(l) => Right(l)
    }
    for {
      lease <- leaseEi
      recipient = lease.recipient.asInstanceOf[Address]
      _ <- Either.cond(lease.isActive, (), GenericError(s"Cannot cancel already cancelled lease"))
      canceller = Address.fromPublicKey(tx.sender.publicKey)
      portfolioDiff <- if (tx.sender == lease.sender) {
        Right(
          Monoid.combine(Map(canceller -> Portfolio(0, LeaseBalance(0, -lease.amount))),
                         Map(recipient -> Portfolio(0, LeaseBalance(-lease.amount, 0)))))
      } else Left(GenericError(s"LeaseTransaction was leased by other sender"))

    } yield Diff(height = height, tx = tx, portfolios = portfolioDiff, leaseState = Map(tx.leaseId -> false))
  }
}
