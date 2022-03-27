package com.ltonetwork.fee

import com.ltonetwork.transaction.ValidationError.GenericError

case class FeeVoteStatus(vote: Byte, description: String, multiplier: Double) {
  def calc(fee: Long): Long = Math.round(fee * multiplier)
  override def toString: String = description
}

object FeeVoteStatus {
  object Decrease extends FeeVoteStatus(-1, "DECREASE", 1 / 1.1)
  object Maintain extends FeeVoteStatus(0, "MAINTAIN", 1)
  object Increase extends FeeVoteStatus(1, "INCREASE", 1.1)

  def apply(description: String): Either[GenericError, FeeVoteStatus] = description.toUpperCase match {
    case Decrease.description => Right(Decrease)
    case Maintain.description => Right(Maintain)
    case Increase.description => Right(Increase)
    case _ => Left(GenericError(s"Invalid fee vote status '$description'"))
  }

  def apply(vote: Byte): FeeVoteStatus = apply(vote.toLong)

  def apply(change: Long): FeeVoteStatus = change match {
    case v if v < 0 => Decrease
    case 0 => Maintain
    case v if v > 0 => Increase
  }
}
