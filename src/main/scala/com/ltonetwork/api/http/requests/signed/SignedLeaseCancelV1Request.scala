package com.ltonetwork.api.http.requests.signed

import com.ltonetwork.account.PublicKeyAccount
import com.ltonetwork.api.http.requests.BroadcastRequest
import com.ltonetwork.transaction.TransactionBuilders.SignatureStringLength
import com.ltonetwork.transaction.ValidationError
import com.ltonetwork.transaction.lease.CancelLeaseTransactionV1
import io.swagger.annotations.{ApiModel, ApiModelProperty}
import play.api.libs.functional.syntax._
import play.api.libs.json._

@ApiModel(value = "Signed Cancel Lease transaction")
case class SignedLeaseCancelV1Request(@ApiModelProperty(value = "Base58 encoded sender public key", required = true)
                                      senderPublicKey: String,
                                      @ApiModelProperty(value = "Base58 encoded lease transaction id", required = true)
                                      txId: String,
                                      @ApiModelProperty(required = true)
                                      timestamp: Long,
                                      @ApiModelProperty(required = true)
                                      signature: String,
                                      @ApiModelProperty(required = true)
                                      fee: Long)
    extends BroadcastRequest {
  def toTx: Either[ValidationError, CancelLeaseTransactionV1] =
    for {
      _sender    <- PublicKeyAccount.fromBase58String(senderPublicKey)
      _signature <- parseBase58(signature, "invalid.signature", SignatureStringLength)
      _leaseTx   <- parseBase58(txId, "invalid.leaseTx", SignatureStringLength)
      _t         <- CancelLeaseTransactionV1.create(_sender, _leaseTx, fee, timestamp, _signature)
    } yield _t
}

object SignedLeaseCancelV1Request {
  implicit val reads: Reads[SignedLeaseCancelV1Request] = (
    (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "txId").read[String].orElse((JsPath \ "leaseId").read[String]) and
      (JsPath \ "timestamp").read[Long] and
      (JsPath \ "signature").read[String] and
      (JsPath \ "fee").read[Long]
  )(SignedLeaseCancelV1Request.apply _)

  implicit val writes: Writes[SignedLeaseCancelV1Request] = Json.writes[SignedLeaseCancelV1Request]
}
