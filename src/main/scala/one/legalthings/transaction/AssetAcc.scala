package one.legalthings.transaction

import one.legalthings.account.Address

case class AssetAcc(account: Address, assetId: Option[AssetId]) {
  lazy val key: String = assetId match {
    case None     => account.address
    case Some(id) => account.address + id.base58
  }
}
