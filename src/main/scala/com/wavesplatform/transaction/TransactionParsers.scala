package com.wavesplatform.transaction

import com.wavesplatform.transaction.lease.{LeaseCancelTransactionV1, LeaseCancelTransactionV2, LeaseTransactionV1, LeaseTransactionV2}
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.transfer._
import com.wavesplatform.utils.base58Length
import scorex.crypto.signatures.Curve25519

import scala.util.{Failure, Success, Try}

object TransactionParsers {

  val TimestampLength            = 8
  val AmountLength               = 8
  val TypeLength                 = 1
  val SignatureStringLength: Int = base58Length(Curve25519.SignatureLength)

  private val old: Map[Byte, TransactionParser] = Seq[TransactionParser](
    GenesisTransaction,
    TransferTransactionV1,
    LeaseTransactionV1,
    LeaseCancelTransactionV1,
    MassTransferTransaction
  ).map { x =>
    x.typeId -> x
  }(collection.breakOut)

  private val modern: Map[(Byte, Byte), TransactionParser] = Seq[TransactionParser](
    AnchorTransaction,
    DataTransaction,
    TransferTransactionV2,
    SetScriptTransaction,
    LeaseTransactionV2,
    LeaseCancelTransactionV2,
    IssueAssociationTransaction,
    RevokeAssociationTransaction,
    SponsorshipTransaction,
    SponsorshipCancelTransaction
  ).flatMap { x =>
    x.supportedVersions.map { version =>
      ((x.typeId, version), x)
    }
  }(collection.breakOut)

  private val all: Map[(Byte, Byte), TransactionParser] = old.flatMap {
    case (typeId, builder) =>
      builder.supportedVersions.map { version =>
        ((typeId, version), builder)
      }
  } ++ modern

  val byName: Map[String, TransactionParser] = (old ++ modern).map {
    case (_, builder) => builder.classTag.runtimeClass.getSimpleName -> builder
  }

  def by(name: String): Option[TransactionParser]                = byName.get(name)
  def by(typeId: Byte, version: Byte): Option[TransactionParser] = all.get((typeId, version))

  def parseBytes(data: Array[Byte]): Try[Transaction] =
    data.headOption
      .fold[Try[Byte]](Failure(new IllegalArgumentException("Can't find the significant byte: the buffer is empty")))(Success(_))
      .flatMap { headByte =>
        if (headByte == 0) modernParseBytes(data)
        else oldParseBytes(headByte, data)
      }

  private def oldParseBytes(tpe: Byte, data: Array[Byte]): Try[Transaction] =
    old
      .get(tpe)
      .fold[Try[TransactionParser]](Failure(new IllegalArgumentException(s"Unknown transaction type (old encoding): '$tpe'")))(Success(_))
      .flatMap(_.parseBytes(data))

  private def modernParseBytes(data: Array[Byte]): Try[Transaction] = {
    if (data.length < 2)
      Failure(new IllegalArgumentException(s"Can't determine the type and the version of transaction: the buffer has ${data.length} bytes"))
    else {
      val Array(_, typeId, version) = data.take(3)
      modern
        .get((typeId, version))
        .fold[Try[TransactionParser]](
          Failure(new IllegalArgumentException(s"Unknown transaction type ($typeId) and version ($version) (modern encoding)")))(Success(_))
        .flatMap(_.parseBytes(data))
    }
  }

}
