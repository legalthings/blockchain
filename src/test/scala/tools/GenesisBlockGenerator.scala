package tools

import java.io.{File, FileNotFoundException}
import com.typesafe.config.ConfigFactory
import com.ltonetwork.crypto
import com.ltonetwork.settings.{GenesisSettings, GenesisTransactionSettings}
import com.ltonetwork.state._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.ltonetwork.account.{Address, AddressScheme, PrivateKeyAccount}
import com.ltonetwork.block.Block
import com.ltonetwork.consensus.nxt.NxtLikeConsensusBlockData
import com.ltonetwork.transaction.Proofs
import com.ltonetwork.transaction.genesis.GenesisTransaction
import com.ltonetwork.wallet.Wallet

import scala.concurrent.duration._

object GenesisBlockGenerator extends App {

  private type SeedText = String
  private type Share    = Long

  case class Settings(networkType: String,
                      initialBalance: Long,
                      baseTarget: Long,
                      averageBlockDelay: FiniteDuration,
                      timestamp: Option[Long],
                      distributions: Map[SeedText, Share]) {
    private val distributionsSum = distributions.values.sum

    require(
      distributionsSum == initialBalance,
      s"The sum of all balances should be == $initialBalance, but it is $distributionsSum"
    )

    val networkByte: Byte = networkType.head.toByte
  }

  case class FullAddressInfo(seedText: SeedText,
                             seed: ByteStr,
                             accountSeed: ByteStr,
                             accountPrivateKey: ByteStr,
                             accountPublicKey: ByteStr,
                             accountAddress: Address)

  private def toFullAddressInfo(seedText: SeedText): FullAddressInfo = {
    val seedHash = seedText.getBytes
    val acc      = Wallet.generateNewAccount(seedHash, 0)

    FullAddressInfo(
      seedText = seedText,
      seed = ByteStr(seedHash),
      accountSeed = ByteStr(acc.seed),
      accountPrivateKey = ByteStr(acc.privateKey),
      accountPublicKey = ByteStr(acc.publicKey),
      accountAddress = acc.toAddress
    )
  }

  private val settings: Settings = {
    implicit val _ = net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase

    val file = new File(args.headOption.getOrElse(throw new IllegalArgumentException("Specify a path to genesis.conf")))
    if (!file.exists()) {
      throw new FileNotFoundException(file.getCanonicalPath)
    }

    ConfigFactory.parseFile(file).as[Settings]("genesis-generator")
  }

  com.ltonetwork.account.AddressScheme.current = new AddressScheme {
    override val chainId: Byte = settings.networkByte
  }

  val shares: Map[FullAddressInfo, Share] = {
    settings.distributions.map { case (seedText, part) => toFullAddressInfo(seedText) -> part }
  }

  val timestamp = settings.timestamp.getOrElse(System.currentTimeMillis())

  val genesisTxs: Seq[GenesisTransaction] = shares.map {
    case (addrInfo, part) =>
      GenesisTransaction(1, 'T', timestamp, addrInfo.accountAddress, part, Proofs.fromSignature(ByteStr.empty))
  }.toSeq

  val genesisBlock: Block = {
    val reference     = ByteStr(Array.fill(crypto.signatureLength)(-1: Byte))
    val genesisSigner = PrivateKeyAccount(Array.empty)

    Block
      .buildAndSign(
        version = 1,
        timestamp = timestamp,
        reference = reference,
        consensusData = NxtLikeConsensusBlockData(settings.baseTarget, ByteStr(Array.fill(crypto.digestLength)(0: Byte))),
        transactionData = genesisTxs,
        signer = genesisSigner,
        featureVotes = Set.empty,
        feeVote = 0
      )
      .explicitGet()
  }

  val signature = genesisBlock.signerData.signature

  report(
    addrInfos = shares.keysIterator,
    settings = GenesisSettings(
      timestamp,
      genesisBlock.timestamp,
      settings.initialBalance,
      Some(signature),
      genesisTxs.map { tx =>
        GenesisTransactionSettings(tx.recipient.stringRepr, tx.amount)
      },
      settings.baseTarget,
      settings.averageBlockDelay
    )
  )

  private def report(addrInfos: Iterator[FullAddressInfo], settings: GenesisSettings): Unit = {

    val output = new StringBuilder
    output.append("Addresses:\n")
    addrInfos.zipWithIndex.foreach {
      case (acc, n) =>
        output.append(s"""($n):
           | Seed text:           ${acc.seedText}
           | Seed:                ${acc.seed}
           | Account seed:        ${acc.accountSeed}
           | Private account key: ${acc.accountPrivateKey}
           | Public account key:  ${acc.accountPublicKey}
           | Account address:     ${acc.accountAddress}
           |
           |""".stripMargin)
    }

    output.append(
      s"""Settings:
         |genesis {
         |  average-block-delay: ${settings.averageBlockDelay.toMillis}ms
         |  initial-base-target: ${settings.initialBaseTarget}
         |  timestamp: ${settings.timestamp}
         |  block-timestamp: ${settings.blockTimestamp}
         |  signature: "${settings.signature.get}"
         |  initial-balance: ${settings.initialBalance}
         |  transactions = [
         |    ${settings.transactions
           .map { x =>
             s"""{recipient: "${x.recipient}", amount: ${x.amount}}"""
           }
           .mkString(",\n    ")}
         |  ]
         |}
         |""".stripMargin
    )

    output.append("\nDon't forget to delete the data!")
    System.out.print(output)

  }
}
