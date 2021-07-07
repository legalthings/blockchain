package com.ltonetwork.transaction.sponsorship

import com.ltonetwork.account.{Address, PrivateKeyAccount, PublicKeyAccount}
import com.ltonetwork.crypto
import com.ltonetwork.state._
import com.ltonetwork.transaction.{Proofs, TransactionBuilder, TransactionSerializer, ValidationError}
import monix.eval.Coeval

import scala.util.{Failure, Success, Try}

case class SponsorshipTransaction private (version: Byte,
                                           chainId: Byte,
                                           timestamp: Long,
                                           sender: PublicKeyAccount,
                                           fee: Long,
                                           recipient: Address,
                                           sponsor: Option[PublicKeyAccount],
                                           proofs: Proofs)
    extends SponsorshipTransactionBase {

  override def builder: TransactionBuilder.For[SponsorshipTransaction]      = SponsorshipTransaction
  private def serializer: TransactionSerializer.For[SponsorshipTransaction] = builder.serializer(version)

  val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(serializer.bodyBytes(this))
}

object SponsorshipTransaction extends TransactionBuilder.For[SponsorshipTransaction] {

  override def typeId: Byte                 = 18
  override def supportedVersions: Set[Byte] = SponsorshipTransactionBase.supportedVersions

  implicit def sign(tx: TransactionT, signer: PrivateKeyAccount, sponsor: Option[PublicKeyAccount]): TransactionT =
    tx.copy(proofs = tx.proofs + signer.sign(tx.bodyBytes()), sponsor = sponsor.otherwise(tx.sponsor))

  object SerializerV1 extends SponsorshipSerializerV1[TransactionT] {
    def createTx(version: Byte,
                 chainId: Byte,
                 timestamp: Long,
                 sender: PublicKeyAccount,
                 fee: Long,
                 recipient: Address,
                 proofs: Proofs): Either[ValidationError, TransactionT] =
      create(version, Some(chainId), timestamp, sender, fee, recipient, None, proofs)
  }

  implicit object Validator extends SponsorshipTransactionBase.Validator[TransactionT]

  override def serializer(version: Byte): TransactionSerializer.For[TransactionT] = version match {
    case 1 => SerializerV1
    case _ => UnknownSerializer
  }

  def create(version: Byte,
             chainId: Option[Byte],
             timestamp: Long,
             sender: PublicKeyAccount,
             fee: Long,
             recipient: Address,
             sponsor: Option[PublicKeyAccount],
             proofs: Proofs): Either[ValidationError, TransactionT] =
    SponsorshipTransaction(version, chainId.getOrElse(networkByte), timestamp, sender, fee, recipient, sponsor, proofs).validatedEither

  def signed(version: Byte,
             timestamp: Long,
             sender: PublicKeyAccount,
             fee: Long,
             recipient: Address,
             sponsor: Option[PublicKeyAccount],
             proofs: Proofs,
             signer: PrivateKeyAccount): Either[ValidationError, TransactionT] =
    create(version, None, timestamp, sender, fee, recipient, sponsor, proofs).signWith(signer)

  def selfSigned(version: Byte, timestamp: Long, sender: PrivateKeyAccount, fee: Long, recipient: Address): Either[ValidationError, TransactionT] =
    signed(version, timestamp, sender, fee, recipient, None, Proofs.empty, sender)
}
