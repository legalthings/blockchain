package com.wavesplatform.api.http

import cats.implicits._
import com.wavesplatform.account.PublicKeyAccount
import com.wavesplatform.state.ByteStr
import com.wavesplatform.transaction.ValidationError.Validation
import com.wavesplatform.transaction.{AnchorTransaction, Proofs, ValidationError}
import io.swagger.annotations.{ApiModel, ApiModelProperty}
import play.api.libs.json.{Format, Json, Writes}

object AnchorRequest {
  implicit val unsignedDataRequestReads = Json.reads[AnchorRequest]
  implicit val signedDataRequestReads   = Json.reads[SignedAnchorRequest]

  def prependZeros(a: ByteStr) =
    if (a.arr.length >= AnchorTransaction.NewEntryLength) a
    else
      ByteStr(Array.fill(AnchorTransaction.NewEntryLength - a.arr.length)(0: Byte) ++ a.arr)
}

case class AnchorRequest(version: Byte, sender: String, anchors: List[String], fee: Long, timestamp: Option[Long] = None)

@ApiModel(value = "Signed Data transaction")
case class SignedAnchorRequest(@ApiModelProperty(required = true)
                               version: Byte,
                               @ApiModelProperty(value = "Base58 encoded sender public key", required = true)
                               senderPublicKey: String,
                               @ApiModelProperty(value = "Anchors to put into blockchain", required = true)
                               anchors: List[String],
                               @ApiModelProperty(required = true)
                               fee: Long,
                               @ApiModelProperty(required = true)
                               timestamp: Long,
                               @ApiModelProperty(required = true)
                               proofs: List[String])
    extends BroadcastRequest {
  def toTx: Either[ValidationError, AnchorTransaction] =
    for {
      _sender     <- PublicKeyAccount.fromBase58String(senderPublicKey)
      _proofBytes <- proofs.traverse(s => parseBase58(s, "invalid proof", Proofs.MaxProofStringSize))
      _anchors    <- anchors.traverse(s => parseBase58(s, "invalid anchor", Proofs.MaxAnchorStringSize))
      _proofs     <- Proofs.create(_proofBytes)
      t           <- AnchorTransaction.create(version, _sender, _anchors.map(AnchorRequest.prependZeros), fee, timestamp, _proofs)
    } yield t
}
