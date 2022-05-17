package com.ltonetwork.transaction.association

import com.ltonetwork.account.Address
import com.ltonetwork.state._
import com.ltonetwork.transaction.Transaction
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json}

// Base class for issue and revoke association transactions
abstract class AssociationTransaction extends Transaction {
  def assocType: Int
  def recipient: Address
  def subject: Option[ByteStr]

  def assoc: (Int, Address, Option[ByteStr]) = (assocType, recipient, subject)
}
