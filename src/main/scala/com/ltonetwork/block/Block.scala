package com.ltonetwork.block

import com.google.common.primitives.{Bytes, Ints, Longs}
import com.ltonetwork.account.{Address, PrivateKeyAccount, PublicKeyAccount}
import com.ltonetwork.consensus.nxt.{NxtConsensusBlockField, NxtLikeConsensusBlockData}
import com.ltonetwork.crypto
import com.ltonetwork.settings.GenesisSettings
import com.ltonetwork.state._
import com.ltonetwork.transaction.ValidationError.GenericError
import com.ltonetwork.transaction._
import com.ltonetwork.transaction.genesis.GenesisTransaction
import com.ltonetwork.utils.ScorexLogging
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json}
import scorex.crypto.signatures.Curve25519._

import java.nio.ByteBuffer
import scala.util.Try

case class Block private (override val timestamp: Long,
                          override val version: Byte,
                          override val reference: ByteStr,
                          override val signerData: SignerData,
                          override val consensusData: NxtLikeConsensusBlockData,
                          transactionData: Seq[Transaction],
                          override val featureVotes: Set[Short],
                          override val feeVote: Byte)
    extends BlockHeader(timestamp, version, reference, signerData, consensusData, transactionData.length, featureVotes, feeVote)
    with Signed {

  val sender: PublicKeyAccount = signerData.generator
  val signature: ByteStr       = signerData.signature

  private val transactionField = TransactionsBlockField(version.toInt, transactionData)

  val uniqueId: ByteStr = signerData.signature

  val bytes: Coeval[Array[Byte]] = Coeval.evalOnce {
    val txBytesSize = transactionField.bytes().length
    val txBytes     = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionField.bytes()

    val cBytesSize = consensusField.bytes().length
    val cBytes     = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusField.bytes()

    val feeVoteBytes = if (version >= 4) Array[Byte](feeVote) else Array.empty[Byte]

    versionField.bytes() ++
      timestampField.bytes() ++
      referenceField.bytes() ++
      cBytes ++
      txBytes ++
      supportedFeaturesField.bytes() ++
      feeVoteBytes ++
      signerField.bytes()
  }

  val json: Coeval[JsObject] = Coeval.evalOnce(
    BlockHeader.json(this, bytes().length) ++ transactionField.json()
  )

  val bytesWithoutSignature: Coeval[Array[Byte]] = Coeval.evalOnce(bytes().dropRight(SignatureLength))

  val blockScore: Coeval[BigInt] = Coeval.evalOnce((BigInt("18446744073709551616") / consensusData.baseTarget).ensuring(_ > 0))

  protected val signatureValid: Coeval[Boolean] =
    Coeval.evalOnce(crypto.verify(signerData.signature.arr, bytesWithoutSignature(), signerData.generator.publicKey))
  protected override val signedDescendants: Coeval[Seq[Signed]] = Coeval.evalOnce(transactionData.flatMap(_.cast[Signed]))

  override def toString: String =
    s"Block(${signerData.signature} -> ${reference.trim}, txs=${transactionData.size}, features=$featureVotes)"

}

object Block extends ScorexLogging {

  case class Fraction(dividend: Int, divider: Int) {
    def apply(l: Long): Long = l / divider * dividend
  }

  val CurrentBlockFeePart: Fraction = Fraction(2, 5)

  type BlockIds = Seq[ByteStr]
  type BlockId = ByteStr

  val MaxTransactionsPerBlockVer1Ver2: Int = 100
  val MaxTransactionsPerBlockVer3: Int = 6000
  val MaxFeaturesInBlock: Int = 64
  val BaseTargetLength: Int = 8
  val GeneratorSignatureLength: Int = 32

  val BlockIdLength: Int = SignatureLength

  val TransactionSizeLength = 4

  def transParseBytes(version: Int, bytes: Array[Byte]): Try[Seq[Transaction]] = Try {
    if (bytes.isEmpty) {
      Seq.empty
    } else {
      val v: (Array[Byte], Int) = version match {
        case 1 | 2 => (bytes.tail, bytes.head) //  127 max, won't work properly if greater
        case 3 | 4 =>
          val size = ByteBuffer.wrap(bytes, 0, 4).getInt()
          (bytes.drop(4), size)
        case _ => ???
      }

      val txs = Seq.newBuilder[Transaction]
      (1 to v._2).foldLeft(0) {
        case (pos, _) =>
          val transactionLengthBytes = v._1.slice(pos, pos + TransactionSizeLength)
          val transactionLength = Ints.fromByteArray(transactionLengthBytes)
          val transactionBytes = v._1.slice(pos + TransactionSizeLength, pos + TransactionSizeLength + transactionLength)
          txs += TransactionBuilders.parseBytes(transactionBytes).get
          pos + TransactionSizeLength + transactionLength
      }

      txs.result()
    }
  }

  def parseBytes(bytes: Array[Byte]): Try[Block] =
    for {
      (blockHeader, transactionBytes) <- BlockHeader.parseBytes(bytes)
      transactionsData <- transParseBytes(blockHeader.version, transactionBytes)
      block <- build(
        blockHeader.version,
        blockHeader.timestamp,
        blockHeader.reference,
        blockHeader.consensusData,
        transactionsData,
        blockHeader.signerData,
        blockHeader.featureVotes,
        blockHeader.feeVote
      ).left.map(ve => new IllegalArgumentException(ve.toString)).toTry
    } yield block

  def areTxsFitInBlock(blockVersion: Byte, txsCount: Int): Boolean = {
    (blockVersion == 3 && txsCount <= MaxTransactionsPerBlockVer3) || (blockVersion <= 2 || txsCount <= MaxTransactionsPerBlockVer1Ver2)
  }

  def build(version: Byte,
            timestamp: Long,
            reference: ByteStr,
            consensusData: NxtLikeConsensusBlockData,
            transactionData: Seq[Transaction],
            signerData: SignerData,
            featureVotes: Set[Short],
            feeVote: Byte): Either[GenericError, Block] = {
    (for {
      _ <- Either.cond(reference.arr.length == SignatureLength, (), "Incorrect reference")
      _ <- Either.cond(consensusData.generationSignature.arr.length == GeneratorSignatureLength, (), "Incorrect consensusData.generationSignature")
      _ <- Either.cond(signerData.generator.publicKey.length == signerData.generator.keyType.length, (), "Incorrect signer.publicKey")
      _ <- Either.cond(version > 2 || featureVotes.isEmpty, (), s"Block version $version could not contain feature votes")
      _ <- Either.cond(featureVotes.size <= MaxFeaturesInBlock, (), s"Block could not contain more than $MaxFeaturesInBlock feature votes")
      _ <- Either.cond(version >= 4 || feeVote == 0, (), s"Block version $version could not contain fee votes")
    } yield Block(timestamp, version, reference, signerData, consensusData, transactionData, featureVotes, feeVote)).left.map(GenericError(_))
  }

  def buildAndSign(version: Byte,
                   timestamp: Long,
                   reference: ByteStr,
                   consensusData: NxtLikeConsensusBlockData,
                   transactionData: Seq[Transaction],
                   signer: PrivateKeyAccount,
                   featureVotes: Set[Short],
                   feeVote: Byte): Either[GenericError, Block] =
    build(version, timestamp, reference, consensusData, transactionData, SignerData(signer, ByteStr.empty), featureVotes, feeVote).right.map(unsigned =>
      unsigned.copy(signerData = SignerData(signer, ByteStr(crypto.sign(signer, unsigned.bytes())))))

  def genesisTransactions(gs: GenesisSettings): Seq[GenesisTransaction] = {
    gs.transactions.map { ts =>
      val acc = Address.fromString(ts.recipient).explicitGet()
      GenesisTransaction.create(acc, ts.amount, gs.timestamp).explicitGet()
    }
  }

  def genesis(genesisSettings: GenesisSettings): Either[ValidationError, Block] = {

    val genesisSigner = PrivateKeyAccount(Array.empty)

    val transactionGenesisData = genesisTransactions(genesisSettings)
    val transactionGenesisDataField = TransactionsBlockField.Version1or2(transactionGenesisData)
    val consensusGenesisData = NxtLikeConsensusBlockData(genesisSettings.initialBaseTarget, ByteStr(Array.fill(crypto.digestLength)(0: Byte)))
    val consensusGenesisDataField = NxtConsensusBlockField(consensusGenesisData)
    val txBytesSize = transactionGenesisDataField.bytes().length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionGenesisDataField.bytes()
    val cBytesSize = consensusGenesisDataField.bytes().length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusGenesisDataField.bytes()

    val reference = Array.fill(SignatureLength)(-1: Byte)

    val timestamp = genesisSettings.blockTimestamp
    val toSign: Array[Byte] = Array(GenesisBlockVersion) ++
      Bytes.ensureCapacity(Longs.toByteArray(timestamp), 8, 0) ++
      reference ++
      cBytes ++
      txBytes ++
      genesisSigner.publicKey

    val signature = genesisSettings.signature.fold(crypto.sign(genesisSigner, toSign))(_.arr)

    if (crypto.verify(signature, toSign, genesisSigner.publicKey))
      Right(
        Block(
          timestamp = timestamp,
          version = GenesisBlockVersion,
          reference = ByteStr(reference),
          signerData = SignerData(genesisSigner, ByteStr(signature)),
          consensusData = consensusGenesisData,
          transactionData = transactionGenesisData,
          featureVotes = Set.empty,
          feeVote = 0
        ))
    else {

      val correctSig = ByteStr(crypto.sign(genesisSigner, toSign))
      if (crypto.verify(correctSig.arr, toSign, genesisSigner.publicKey))
        Left(GenericError(s"Passed genesis signature is not valid. should be: $correctSig "))
      else Left(GenericError("pizdets"))
    }
  }

  val GenesisBlockVersion: Byte = 1
}