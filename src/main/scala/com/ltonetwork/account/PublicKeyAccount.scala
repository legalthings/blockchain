package com.ltonetwork.account

import com.ltonetwork.account.KeyTypes._
import com.ltonetwork.crypto
import com.ltonetwork.utils.base58Length
import com.ltonetwork.utils.Base58
import com.ltonetwork.transaction.ValidationError.InvalidAddress

trait PublicKeyAccount {
  def keyType: KeyType
  def publicKey: Array[Byte]

  override def equals(b: Any): Boolean = b match {
    case a: PublicKeyAccount => publicKey.sameElements(a.publicKey)
    case _                   => false
  }

  override def hashCode(): Int = publicKey.hashCode()

  override lazy val toString: String = this.toAddress.address

  def verify(signature: Array[Byte], message: Array[Byte]): Boolean = crypto.verify(signature, message, this)
}

object PublicKeyAccount {
  private case class PublicKeyAccountImpl(keyType: KeyType, publicKey: Array[Byte]) extends PublicKeyAccount

  def apply(publicKey: Array[Byte]): PublicKeyAccount                   = PublicKeyAccountImpl(ED25519, publicKey)
  def apply(keyType: KeyType, publicKey: Array[Byte]): PublicKeyAccount = PublicKeyAccountImpl(keyType, publicKey)

  implicit def toAddress(publicKeyAccount: PublicKeyAccount): Address = Address.fromPublicKey(publicKeyAccount.publicKey)

  implicit class PublicKeyAccountExt(pk: PublicKeyAccount) {
    def toAddress: Address = PublicKeyAccount.toAddress(pk)
  }

  object Dummy extends PublicKeyAccount {
    def keyType: KeyType       = ED25519
    def publicKey: Array[Byte] = Array[Byte](0)
  }

  def fromBase58String(keyType: KeyType, s: String): Either[InvalidAddress, PublicKeyAccount] =
    (for {
      bytes <- Base58.decode(s).toEither.left.map(ex => s"Unable to decode base58: ${ex.getMessage}")
      _     <- Either.cond(
                 bytes.length <= keyType.length,
                 (),
                 s"Expected ${keyType.reference} public key to be ${keyType.length} bytes, got ${bytes.length} bytes"
               )
    } yield PublicKeyAccount(keyType, bytes)).left.map(InvalidAddress)

  def fromBase58String(s: String): Either[InvalidAddress, PublicKeyAccount] =
    fromBase58String(ED25519, s)
}
