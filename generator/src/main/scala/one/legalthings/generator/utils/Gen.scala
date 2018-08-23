package one.legalthings.generator.utils

import java.util.concurrent.ThreadLocalRandom

import one.legalthings.generator.utils.Implicits._
import one.legalthings.state.ByteStr
import one.legalthings.account.{Address, PrivateKeyAccount}
import one.legalthings.transaction.smart.script.{Script, ScriptCompiler}
import one.legalthings.transaction.transfer.MassTransferTransaction.ParsedTransfer
import one.legalthings.transaction.transfer._
import one.legalthings.transaction.{Proofs, Transaction}
import scorex.crypto.signatures.Curve25519._

object Gen {
  private def random = ThreadLocalRandom.current

  def multiSigScript(owners: Seq[PrivateKeyAccount], requiredProofsCount: Int): Script = {
    val accountsWithIndexes = owners.zipWithIndex
    val keyLets =
      accountsWithIndexes map {
        case (acc, i) =>
          s"let accountPK$i = base58'${ByteStr(acc.publicKey).base58}'"
      } mkString "\n"

    val signedLets =
      accountsWithIndexes map {
        case (_, i) =>
          s"let accountSigned$i = if(sigVerify(tx.bodyBytes, tx.proofs[$i], accountPK$i)) then 1 else 0"
      } mkString "\n"

    val proofSum = accountsWithIndexes map {
      case (_, ind) =>
        s"accountSigned$ind"
    } mkString ("let proofSum = ", " + ", "")

    val finalStatement = s"proofSum >= $requiredProofsCount"

    val src =
      s"""
       |$keyLets
       |
       |$signedLets
       |
       |$proofSum
       |
       |$finalStatement
      """.stripMargin

    val (script, _) = ScriptCompiler(src)
      .explicitGet()

    script
  }

  def txs(minFee: Long, maxFee: Long, senderAccounts: Seq[PrivateKeyAccount], recipientGen: Iterator[Address]): Iterator[Transaction] = {
    val senderGen = Iterator.randomContinually(senderAccounts)
    val feeGen    = Iterator.continually(minFee + random.nextLong(maxFee - minFee))
    transfers(senderGen, recipientGen, feeGen)
  }

  def transfers(senderGen: Iterator[PrivateKeyAccount], recipientGen: Iterator[Address], feeGen: Iterator[Long]): Iterator[Transaction] = {
    senderGen
      .zip(recipientGen)
      .zip(feeGen)
      .map {
        case ((src, dst), fee) =>
          TransferTransactionV1.selfSigned(None, src, dst, fee, System.currentTimeMillis(), None, fee, Array.emptyByteArray)
      }
      .collect { case Right(x) => x }
  }

  def massTransfers(senderGen: Iterator[PrivateKeyAccount], recipientGen: Iterator[Address], amountGen: Iterator[Long]): Iterator[Transaction] = {
    val transferCountGen = Iterator.continually(random.nextInt(MassTransferTransaction.MaxTransferCount + 1))
    senderGen
      .zip(transferCountGen)
      .map {
        case (sender, count) =>
          val transfers = List.tabulate(count)(_ => ParsedTransfer(recipientGen.next(), amountGen.next()))
          val fee       = 100000 + count * 50000
          MassTransferTransaction.selfSigned(Proofs.Version, None, sender, transfers, System.currentTimeMillis, fee, Array.emptyByteArray)
      }
      .collect { case Right(tx) => tx }
  }

  val address: Iterator[Address] = Iterator.continually {
    val pk = Array.fill[Byte](KeyLength)(random.nextInt(Byte.MaxValue).toByte)
    Address.fromPublicKey(pk)
  }

  def address(uniqNumber: Int): Iterator[Address] = Iterator.randomContinually(address.take(uniqNumber).toSeq)

  def address(limitUniqNumber: Option[Int]): Iterator[Address] = limitUniqNumber.map(address(_)).getOrElse(address)

}
