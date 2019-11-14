package com.wavesplatform.database

import java.nio.ByteBuffer

import com.google.common.base.Charsets.UTF_8
import com.google.common.io.ByteStreams.{newDataInput, newDataOutput}
import com.google.common.primitives.{Ints, Longs, Shorts}
import com.wavesplatform.account.{Address, Alias}
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.state.{_}
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.transaction.smart.script.{Script, ScriptReader}

object Keys {
  type Height = Int
  type TxNum  = Short

  def readTransactionHNSeqAndType(bs: Array[Byte]): (Height, Seq[(Byte, TxNum)]) = {
    val ndi          = newDataInput(bs)
    val height       = (ndi.readInt())
    val numSeqLength = ndi.readInt()

    (height, List.fill(numSeqLength) {
      val tp  = ndi.readByte()
      val num = (ndi.readShort())
      (tp, num)
    })
  }

  def writeTransactionHNSeqAndType(v: (Height, Seq[(Byte, TxNum)])): Array[Byte] = {
    val (height, numSeq) = v
    val numSeqLength     = numSeq.length

    val outputLength = 4 + 4 + numSeqLength * (4 + 1)
    val ndo          = newDataOutput(outputLength)

    ndo.writeInt(height)
    ndo.writeInt(numSeqLength)
    numSeq.foreach {
      case (tp, num) =>
        ndo.writeByte(tp)
        ndo.writeShort(num)
    }

    ndo.toByteArray
  }

  val AddressTransactionHNPrefix: Short = 53
  def addressTransactionHN(addressId: BigInt, seqNr: Int): Key[Option[(Int, Seq[(Byte, Short)])]] =
    Key.opt(
      "address-transaction-height-type-and-nums",
      hBytes(AddressTransactionHNPrefix, seqNr, addressId.toByteArray),
      readTransactionHNSeqAndType,
      writeTransactionHNSeqAndType
    )

  private def h(prefix: Short, height: Int): Array[Byte] =
    ByteBuffer.allocate(6).putShort(prefix).putInt(height).array()

  private def hBytes(prefix: Short, height: Int, bytes: Array[Byte]) =
    ByteBuffer.allocate(6 + bytes.length).putShort(prefix).putInt(height).put(bytes).array()

  private def bytes(prefix: Short, bytes: Array[Byte]) =
    ByteBuffer.allocate(2 + bytes.length).putShort(prefix).put(bytes).array()

  private def addr(prefix: Short, addressId: BigInt) = bytes(prefix, addressId.toByteArray)

  private def hash(prefix: Short, hashBytes: ByteStr) = bytes(prefix, hashBytes.arr)

  private def hAddr(prefix: Short, height: Int, addressId: BigInt): Array[Byte] = hBytes(prefix, height, addressId.toByteArray)

  private def historyKey(prefix: Short, b: Array[Byte]) = Key(bytes(prefix, b), readIntSeq, writeIntSeq)

  private def intKey(prefix: Short, default: Int = 0): Key[Int] =
    Key(Shorts.toByteArray(prefix), Option(_).fold(default)(Ints.fromByteArray), Ints.toByteArray)

  private def bytesSeqNr(prefix: Short, b: Array[Byte]): Key[Int] =
    Key(bytes(prefix, b), Option(_).fold(0)(Ints.fromByteArray), Ints.toByteArray)

  private def unsupported[A](message: String): A => Array[Byte] = _ => throw new UnsupportedOperationException(message)

  // actual key definition

  val version: Key[Int]               = intKey(0, default = 1)
  val height: Key[Int]                = intKey(1)
  def score(height: Int): Key[BigInt] = Key(h(2, height), Option(_).fold(BigInt(0))(BigInt(_)), _.toByteArray)

  private def blockAtHeight(height: Int) = h(3, height)

  def blockAt(height: Int): Key[Option[Block]]          = Key.opt[Block](blockAtHeight(height), Block.parseBytes(_).get, _.bytes())
  def blockBytes(height: Int): Key[Option[Array[Byte]]] = Key.opt[Array[Byte]](blockAtHeight(height), identity, identity)
  def blockHeader(height: Int): Key[Option[(BlockHeader, Int)]] =
    Key.opt[(BlockHeader, Int)](blockAtHeight(height), b => (BlockHeader.parseBytes(b).get._1, b.length), unsupported("Can't write block headers")) // this dummy encoder is never used: we only store blocks, not block headers

  def heightOf(blockId: ByteStr): Key[Option[Int]] = Key.opt[Int](hash(4, blockId), Ints.fromByteArray, Ints.toByteArray)

  def wavesBalanceHistory(addressId: BigInt): Key[Seq[Int]] = historyKey(5, addressId.toByteArray)
  def wavesBalance(addressId: BigInt)(height: Int): Key[Long] =
    Key(hAddr(6, height, addressId), Option(_).fold(0L)(Longs.fromByteArray), Longs.toByteArray)

  def assetList(addressId: BigInt): Key[Set[ByteStr]]                         = Key(addr(7, addressId), readTxIds(_).toSet, assets => writeTxIds(assets.toSeq))
  def assetBalanceHistory(addressId: BigInt, assetId: ByteStr): Key[Seq[Int]] = historyKey(8, addressId.toByteArray ++ assetId.arr)
  def assetBalance(addressId: BigInt, assetId: ByteStr)(height: Int): Key[Long] =
    Key(hBytes(9, height, addressId.toByteArray ++ assetId.arr), Option(_).fold(0L)(Longs.fromByteArray), Longs.toByteArray)

  def assetInfoHistory(assetId: ByteStr): Key[Seq[Int]]        = historyKey(10, assetId.arr)
  def assetInfo(assetId: ByteStr)(height: Int): Key[AssetInfo] = Key(hBytes(11, height, assetId.arr), readAssetInfo, writeAssetInfo)

  def leaseBalanceHistory(addressId: BigInt): Key[Seq[Int]] = historyKey(12, addressId.toByteArray)
  def leaseBalance(addressId: BigInt)(height: Int): Key[LeaseBalance] =
    Key(hAddr(13, height, addressId), readLeaseBalance, writeLeaseBalance)
  def leaseStatusHistory(leaseId: ByteStr): Key[Seq[Int]] = historyKey(14, leaseId.arr)
  def leaseStatus(leaseId: ByteStr)(height: Int): Key[Boolean] =
    Key(hBytes(15, height, leaseId.arr), _(0) == 1, active => Array[Byte](if (active) 1 else 0))

  def filledVolumeAndFeeHistory(orderId: ByteStr): Key[Seq[Int]]           = historyKey(16, orderId.arr)
  def filledVolumeAndFee(orderId: ByteStr)(height: Int): Key[VolumeAndFee] = Key(hBytes(17, height, orderId.arr), readVolumeAndFee, writeVolumeAndFee)

  def transactionInfo(txId: ByteStr): Key[Option[(Int, Transaction)]] = Key.opt(hash(18, txId), readTransactionInfo, writeTransactionInfo)
  def transactionHeight(txId: ByteStr): Key[Option[Int]] =
    Key.opt(hash(18, txId), readTransactionHeight, unsupported("Can't write transaction height only"))

  // 19, 20 was never used

  def changedAddresses(height: Int): Key[Seq[BigInt]] = Key(h(21, height), readBigIntSeq, writeBigIntSeq)

  def transactionIdsAtHeight(height: Int): Key[Seq[ByteStr]] = Key(h(22, height), readTxIds, writeTxIds)

  def addressIdOfAlias(alias: Alias): Key[Option[BigInt]] = Key.opt(bytes(23, alias.bytes.arr), BigInt(_), _.toByteArray)

  val lastAddressId: Key[Option[BigInt]] = Key.opt(Array[Byte](0, 24), BigInt(_), _.toByteArray)

  def addressId(address: Address): Key[Option[BigInt]] = Key.opt(bytes(25, address.bytes.arr), BigInt(_), _.toByteArray)
  def idToAddress(id: BigInt): Key[Address]            = Key(bytes(26, id.toByteArray), Address.fromBytes(_).explicitGet(), _.bytes.arr)

  def addressScriptHistory(addressId: BigInt): Key[Seq[Int]] = historyKey(27, addressId.toByteArray)
  def addressScript(addressId: BigInt)(height: Int): Key[Option[Script]] =
    Key.opt(hAddr(28, height, addressId), ScriptReader.fromBytes(_).explicitGet(), _.bytes().arr)

  def approvedFeatures: Key[Map[Short, Int]]  = Key(Array[Byte](0, 29), readFeatureMap, writeFeatureMap)
  def activatedFeatures: Key[Map[Short, Int]] = Key(Array[Byte](0, 30), readFeatureMap, writeFeatureMap)

  def dataKeyChunkCount(addressId: BigInt): Key[Int] = Key(addr(31, addressId), Option(_).fold(0)(Ints.fromByteArray), Ints.toByteArray)
  def dataKeyChunk(addressId: BigInt, chunkNo: Int): Key[Seq[String]] =
    Key(addr(32, addressId) ++ Ints.toByteArray(chunkNo), readStrings, writeStrings)

  def dataHistory(addressId: BigInt, key: String): Key[Seq[Int]] = historyKey(33, addressId.toByteArray ++ key.getBytes(UTF_8))
  def data(addressId: BigInt, key: String)(height: Int): Key[Option[DataEntry[_]]] =
    Key.opt(hBytes(34, height, addressId.toByteArray ++ key.getBytes(UTF_8)), DataEntry.parseValue(key, _, 0)._1, _.valueBytes)

  def sponsorshipHistory(assetId: ByteStr): Key[Seq[Int]]               = historyKey(35, assetId.arr)
  def sponsorship(assetId: ByteStr)(height: Int): Key[SponsorshipValue] = Key(hBytes(36, height, assetId.arr), readSponsorship, writeSponsorship)

  val addressesForWavesSeqNr: Key[Int]                = intKey(37)
  def addressesForWaves(seqNr: Int): Key[Seq[BigInt]] = Key(h(38, seqNr), readBigIntSeq, writeBigIntSeq)

  def addressesForAssetSeqNr(assetId: ByteStr): Key[Int]                = bytesSeqNr(39, assetId.arr)
  def addressesForAsset(assetId: ByteStr, seqNr: Int): Key[Seq[BigInt]] = Key(hBytes(40, seqNr, assetId.arr), readBigIntSeq, writeBigIntSeq)

  def addressTransactionSeqNr(addressId: BigInt): Key[Int] = bytesSeqNr(41, addressId.toByteArray)
  def addressTransactionIds(addressId: BigInt, seqNr: Int): Key[Seq[(Int, ByteStr)]] =
    Key(hBytes(42, seqNr, addressId.toByteArray), readTransactionIds, writeTransactionIds)

  val AliasIsDisabledPrefix: Short = 43
  def aliasIsDisabled(alias: Alias): Key[Boolean] =
    Key(bytes(AliasIsDisabledPrefix, alias.bytes.arr), Option(_).exists(_(0) == 1), if (_) Array[Byte](1) else Array[Byte](0))

  def outgoingAssociationsSeqNr(address: ByteStr): Key[Int] = bytesSeqNr(44, address.arr)
  def outgoingAssociationTransactionId(addressBytes: ByteStr, seqNr: Int): Key[Array[Byte]] =
    Key(hBytes(45, seqNr, addressBytes.arr), identity, identity)

  def incomingAssociationsSeqNr(address: ByteStr): Key[Int] = bytesSeqNr(46, address.arr)
  def incomingAssociationTransactionId(addressBytes: ByteStr, seqNr: Int): Key[Array[Byte]] =
    Key(hBytes(47, seqNr, addressBytes.arr), identity, identity)

}
