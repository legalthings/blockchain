package com.wavesplatform.matcher.api

import com.wavesplatform.account.Address
import com.wavesplatform.database.{DBExt, RW, ReadOnlyDB}
import com.wavesplatform.matcher.{ActiveOrdersIndex, FinalizedOrdersCommonIndex, FinalizedOrdersPairIndex, MatcherKeys}
import com.wavesplatform.matcher.model.OrderInfo
import com.wavesplatform.state.ByteStr
import com.wavesplatform.transaction.AssetId
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order}
import org.iq80.leveldb.DB

import scala.collection.mutable

object DBUtils {
  import OrderInfo.orderStatusOrdering

  def ordersByAddressAndPair(db: DB, address: Address, pair: AssetPair, maxOrders: Int): IndexedSeq[(Order, OrderInfo)] = db.readOnly { ro =>
    mergeOrders(
      ro,
      maxOrders,
      activeIndex = new ActiveOrdersIndex(address, 200).iterator(ro).collect { case (`pair`, id) => id },
      nonActiveIndex = new FinalizedOrdersPairIndex(address, pair, 100).iterator(ro)
    )
  }

  /**
    * @param activeOnly If false - returns all active orders and the (maxOrders - allActiveOrders.size) recent of others
    */
  def ordersByAddress(db: DB, address: Address, activeOnly: Boolean, maxOrders: Int): IndexedSeq[(Order, OrderInfo)] = db.readOnly { ro =>
    mergeOrders(
      ro,
      maxOrders,
      activeIndex = new ActiveOrdersIndex(address, 200).iterator(ro).map { case (_, id) => id },
      nonActiveIndex = if (activeOnly) Iterator.empty else new FinalizedOrdersCommonIndex(address, 100).iterator(ro)
    )
  }

  private def mergeOrders(ro: ReadOnlyDB,
                          maxOrders: Int,
                          activeIndex: Iterator[Order.Id],
                          nonActiveIndex: Iterator[Order.Id]): IndexedSeq[(Order, OrderInfo)] = {
    def get(id: Order.Id): (Option[Order], Option[OrderInfo]) = (ro.get(MatcherKeys.order(id)), ro.get(MatcherKeys.orderInfoOpt(id)))

    val active = activeIndex.take(maxOrders).map(get).collect { case (Some(o), Some(oi)) => (o, oi) }.toIndexedSeq
    println(s"active (size=${active.size}): ${active.mkString(", ")}")

    val nonActive = nonActiveIndex
    // .take(maxOrders - active.size)
      .map(get)
      .collect { case (Some(o), Some(oi)) => (o, oi) }
      .take(maxOrders - active.size)
      .toVector
    println(s"nonActive (size=${nonActive.size}, took ${maxOrders - active.size}): ${nonActive.mkString(", ")}, nonActive: $nonActive")

    val d = mutable.Set.empty[Order.Id]
    (active ++ nonActive)
      .filter {
        case (o, _) =>
          val isNew = !d.contains(o.id())
          if (isNew) d.add(o.id())
          isNew
      }
      .sortBy { case (order, info) => (info.status, -order.timestamp) }
  }

  def reservedBalance(db: DB, address: Address): Map[Option[AssetId], Long] = db.readOnly { ro =>
    (for {
      idx <- 1 to ro.get(MatcherKeys.openVolumeSeqNr(address))
      assetId = ro.get(MatcherKeys.openVolumeAsset(address, idx))
      volume <- ro.get(MatcherKeys.openVolume(address, assetId))
      if volume != 0
    } yield assetId -> volume).toMap
  }

  def order(db: DB, orderId: ByteStr): Option[Order] = db.get(MatcherKeys.order(orderId))

  def orderInfo(db: DB, orderId: ByteStr): OrderInfo = db.get(MatcherKeys.orderInfo(orderId))
  def orderInfo(rw: RW, orderId: ByteStr): OrderInfo = rw.get(MatcherKeys.orderInfo(orderId))
}
