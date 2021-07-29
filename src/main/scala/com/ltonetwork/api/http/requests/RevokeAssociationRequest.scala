package com.ltonetwork.api.http.requests

import com.ltonetwork.account.{Address, PrivateKeyAccount, PublicKeyAccount}
import com.ltonetwork.state.ByteStr
import com.ltonetwork.transaction.association.RevokeAssociationTransaction
import com.ltonetwork.transaction.{Proofs, ValidationError}
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class RevokeAssociationRequest(version: Option[Byte] = None,
                                    timestamp: Option[Long] = None,
                                    senderKeyType: Option[String] = None,
                                    senderPublicKey: Option[String] = None,
                                    fee: Long,
                                    recipient: String,
                                    associationType: Int,
                                    hash: Option[ByteStr] = None,
                                    sponsorKeyType: Option[String] = None,
                                    sponsorPublicKey: Option[String] = None,
                                    signature: Option[ByteStr] = None,
                                    proofs: Option[Proofs] = None,
    ) extends TxRequest.For[RevokeAssociationTransaction] {

  protected def sign(tx: RevokeAssociationTransaction, signer: PrivateKeyAccount): RevokeAssociationTransaction = tx.signWith(signer)

  def toTxFrom(sender: PublicKeyAccount, sponsor: Option[PublicKeyAccount]): Either[ValidationError, RevokeAssociationTransaction] =
    for {
      validRecipient <- Address.fromString(recipient)
      validProofs <- toProofs(signature, proofs)
      tx <- RevokeAssociationTransaction.create(
        version.getOrElse(RevokeAssociationTransaction.latestVersion),
        None,
        timestamp.getOrElse(defaultTimestamp),
        sender,
        fee,
        validRecipient,
        associationType,
        hash.noneIfEmpty,
        sponsor,
        validProofs
      )
    } yield tx
}

object RevokeAssociationRequest {
  implicit val jsonFormat: Format[RevokeAssociationRequest] = Format(
    ((JsPath \ "version").readNullable[Byte] and
      (JsPath \ "timestamp").readNullable[Long] and
      (JsPath \ "senderKeyType").readNullable[String] and
      (JsPath \ "senderPublicKey").readNullable[String] and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "recipient").read[String].orElse((JsPath \ "party").read[String]) and
      (JsPath \ "associationType").read[Int] and
      (JsPath \ "hash").readNullable[ByteStr] and
      (JsPath \ "sponsorKeyType").readNullable[String] and
      (JsPath \ "sponsorPublicKey").readNullable[String] and
      (JsPath \ "signature").readNullable[ByteStr] and
      (JsPath \ "proofs").readNullable[Proofs])(RevokeAssociationRequest.apply _),
    Json.writes[RevokeAssociationRequest].transform((json: JsObject) => Json.obj("type" -> RevokeAssociationTransaction.typeId.toInt) ++ json)
  )
}