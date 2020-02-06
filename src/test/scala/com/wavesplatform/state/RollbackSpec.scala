package com.wavesplatform.state

import com.wavesplatform.account.{Address, PrivateKeyAccount}
import com.wavesplatform.crypto.SignatureLength
import com.wavesplatform.db.WithState
import com.wavesplatform.features._
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.{FunctionalitySettings, TestFunctionalitySettings}
import com.wavesplatform.state.reader.LeaseDetails
import com.wavesplatform.transaction.lease.{LeaseCancelTransactionV1, LeaseTransactionV1}
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.transfer._
import com.wavesplatform.transaction._
import com.wavesplatform.{NoShrink, TestTime, TransactionGen, history}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FreeSpec, Matchers}

class RollbackSpec extends FreeSpec with Matchers with WithState with TransactionGen with PropertyChecks with NoShrink {
  private val time   = new TestTime
  private def nextTs = time.getTimestamp()

  private def genesisBlock(genesisTs: Long, address: Address, initialBalance: Long) = TestBlock.create(
    genesisTs,
    ByteStr(Array.fill[Byte](SignatureLength)(0)),
    Seq(GenesisTransaction.create(address, initialBalance, genesisTs).explicitGet())
  )
  private def genesisBlock(genesisTs: Long, balances: Seq[(Address, Long)]) =
    TestBlock.create(genesisTs, ByteStr(Array.fill[Byte](SignatureLength)(0)), balances.map {
      case (addr, amt) => GenesisTransaction.create(addr, amt, genesisTs).explicitGet()
    })

  private val enoughFee = 100000000L
  private def transfer(sender: PrivateKeyAccount, recipient: Address, amount: Long) =
    TransferTransactionV1.selfSigned(sender, recipient, amount, nextTs, enoughFee, Array.empty[Byte]).explicitGet()

  private def randomOp(sender: PrivateKeyAccount, recipient: Address, amount: Long, op: Int) = {
    import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
    op match {
      case 1 =>
        val lease = LeaseTransactionV1.selfSigned(sender, amount, enoughFee, nextTs, recipient).explicitGet()
        List(lease, LeaseCancelTransactionV1.selfSigned(sender, lease.id(), enoughFee, nextTs).explicitGet())
      case 2 =>
        List(
          MassTransferTransaction
            .selfSigned(1, sender, List(ParsedTransfer(recipient, amount), ParsedTransfer(recipient, amount)), nextTs, enoughFee, Array.empty[Byte])
            .explicitGet())
      case _ => List(TransferTransactionV1.selfSigned(sender, recipient, amount, nextTs, enoughFee, Array.empty[Byte]).explicitGet())
    }
  }

  "Rollback resets" - {
    "Rollback save dropped blocks order" in forAll(accountGen, positiveLongGen, Gen.choose(1, 10)) {
      case (sender, initialBalance, blocksCount) =>
        withDomain() { d =>
          d.appendBlock(genesisBlock(nextTs, sender, initialBalance))
          val genesisSignature = d.lastBlockId
          def newBlocks(i: Int): List[ByteStr] = {
            if (i == blocksCount) {
              Nil
            } else {
              val block = TestBlock.create(nextTs + i, d.lastBlockId, Seq())
              d.appendBlock(block)
              block.uniqueId :: newBlocks(i + 1)
            }
          }
          val blocks        = newBlocks(0)
          val droppedBlocks = d.removeAfter(genesisSignature)
          droppedBlocks(0).reference shouldBe genesisSignature
          droppedBlocks.map(_.uniqueId).toList shouldBe blocks
          droppedBlocks foreach d.appendBlock
        }
    }

    "forget rollbacked transaction for quering" in forAll(accountGen, accountGen, Gen.nonEmptyListOf(Gen.choose(1, 10))) {
      case (sender, recipient, txCount) =>
        val settings = createSettings(BlockchainFeatures.MassTransfer -> 0)
        val wavesSettings = history.DefaultWavesSettings.copy(
          blockchainSettings = history.DefaultWavesSettings.blockchainSettings.copy(functionalitySettings = settings))
        withDomain(wavesSettings) { d =>
          d.appendBlock(genesisBlock(nextTs, sender, com.wavesplatform.state.diffs.ENOUGH_AMT))

          val genesisSignature = d.lastBlockId

          val transferAmount = 100

          val transfers = txCount.map(tc => Seq.fill(tc)(randomOp(sender, recipient, transferAmount, tc % 3)).flatten)

          for (transfer <- transfers) {
            d.appendBlock(
              TestBlock.create(
                nextTs,
                d.lastBlockId,
                transfer
              ))
          }

          val stransactions1 = d.addressTransactions(sender).sortBy(_._2.timestamp)
          val rtransactions1 = d.addressTransactions(recipient).sortBy(_._2.timestamp)

          d.removeAfter(genesisSignature)

          for (transfer <- transfers) {
            d.appendBlock(
              TestBlock.create(
                nextTs,
                d.lastBlockId,
                transfer
              ))
          }

          val stransactions2 = d.addressTransactions(sender).sortBy(_._2.timestamp)
          val rtransactions2 = d.addressTransactions(recipient).sortBy(_._2.timestamp)

          stransactions1 shouldBe stransactions2
          rtransactions1 shouldBe rtransactions2
        }
    }

    "waves balances" in forAll(accountGen, positiveLongGen, accountGen, Gen.nonEmptyListOf(Gen.choose(1, 10))) {
      case (sender, initialBalance, recipient, txCount) =>
        withDomain() { d =>
          d.appendBlock(genesisBlock(nextTs, sender, initialBalance))

          val genesisSignature = d.lastBlockId

          d.portfolio(sender.toAddress).balance shouldBe initialBalance
          d.portfolio(recipient.toAddress).balance shouldBe 0

          val totalTxCount   = txCount.sum
          val transferAmount = initialBalance / (totalTxCount * 2)

          for (tc <- txCount) {
            d.appendBlock(
              TestBlock.create(
                nextTs,
                d.lastBlockId,
                Seq.fill(tc)(transfer(sender, recipient, transferAmount))
              ))
          }

          d.portfolio(recipient).balance shouldBe (transferAmount * totalTxCount)

          d.removeAfter(genesisSignature)

          d.portfolio(sender).balance shouldBe initialBalance
          d.portfolio(recipient).balance shouldBe 0
        }
    }

    "lease balances and states" in forAll(accountGen, positiveLongGen suchThat (_ > enoughFee * 2), accountGen) {
      case (sender, initialBalance, recipient) =>
        withDomain() { d =>
          d.appendBlock(genesisBlock(nextTs, sender, initialBalance))
          val genesisBlockId = d.lastBlockId

          val leaseAmount = initialBalance - enoughFee * 2
          val lt          = LeaseTransactionV1.selfSigned(sender, leaseAmount, enoughFee, nextTs, recipient).explicitGet()
          d.appendBlock(TestBlock.create(nextTs, genesisBlockId, Seq(lt)))
          val blockWithLeaseId = d.lastBlockId
          d.blockchainUpdater.leaseDetails(lt.id()) should contain(LeaseDetails(sender, recipient, 2, leaseAmount, true))
          d.portfolio(sender).lease.out shouldEqual leaseAmount
          d.portfolio(recipient).lease.in shouldEqual leaseAmount

          d.appendBlock(
            TestBlock.create(
              nextTs,
              blockWithLeaseId,
              Seq(LeaseCancelTransactionV1.selfSigned(sender, lt.id(), enoughFee, nextTs).explicitGet())
            ))
          d.blockchainUpdater.leaseDetails(lt.id()) should contain(LeaseDetails(sender, recipient, 2, leaseAmount, false))
          d.portfolio(sender).lease.out shouldEqual 0
          d.portfolio(recipient).lease.in shouldEqual 0

          d.removeAfter(blockWithLeaseId)
          d.blockchainUpdater.leaseDetails(lt.id()) should contain(LeaseDetails(sender, recipient, 2, leaseAmount, true))
          d.portfolio(sender).lease.out shouldEqual leaseAmount
          d.portfolio(recipient).lease.in shouldEqual leaseAmount

          d.removeAfter(genesisBlockId)
          d.blockchainUpdater.leaseDetails(lt.id()) shouldBe 'empty
          d.portfolio(sender).lease.out shouldEqual 0
          d.portfolio(recipient).lease.in shouldEqual 0
        }
    }

    "data transaction" in (forAll(accountGen, positiveLongGen, dataEntryGen(1000)) {
      case (sender, initialBalance, dataEntry) =>
        withDomain() { d =>
          d.appendBlock(genesisBlock(nextTs, sender, initialBalance))
          val genesisBlockId = d.lastBlockId

          d.appendBlock(
            TestBlock.create(
              nextTs,
              genesisBlockId,
              Seq(DataTransaction.selfSigned(1, sender, List(dataEntry), enoughFee, nextTs).explicitGet())
            ))

          d.blockchainUpdater.accountData(sender, dataEntry.key) should contain(dataEntry)

          d.removeAfter(genesisBlockId)
          d.blockchainUpdater.accountData(sender, dataEntry.key) shouldBe 'empty
        }
    })

    "anchor transaction" in (forAll(accountGen, positiveLongGen, Gen.choose(0, AnchorTransaction.MaxEntryCount).flatMap(Gen.listOfN(_, bytes64gen))) {
      case (sender, initialBalance, anchors) =>
        withDomain() { d =>
          d.appendBlock(genesisBlock(nextTs, sender, initialBalance))
          val genesisBlockId = d.lastBlockId

          val tx = AnchorTransaction.selfSigned(1, sender, anchors.map(ByteStr(_)), enoughFee, nextTs).explicitGet()
          d.appendBlock(
            TestBlock.create(
              nextTs,
              genesisBlockId,
              Seq(tx)
            ))

          d.blockchainUpdater.containsTransaction(tx.id()) shouldBe true

          d.removeAfter(genesisBlockId)

          d.blockchainUpdater.containsTransaction(tx.id()) shouldBe false
        }
    })

    "sponsorship transaction" in forAll(accountGen, accountGen) {

      case (sponsor, sender) =>
        import com.wavesplatform.state.diffs.ENOUGH_AMT
        val settings = createSettings(BlockchainFeatures.SponsorshipTransaction -> 0, BlockchainFeatures.SmartAccounts -> 0)
        val wavesSettings = history.DefaultWavesSettings.copy(
          blockchainSettings = history.DefaultWavesSettings.blockchainSettings.copy(functionalitySettings = settings))
        val tx = SponsorshipTransaction.selfSigned(1, sponsor, sender, 5 * 100000000L, nextTs).explicitGet()
        val tx2 = SponsorshipCancelTransaction.selfSigned(1, sponsor, sender, 5 * 100000000L, nextTs).explicitGet()

        withDomain(wavesSettings) { d =>
          d.appendBlock(genesisBlock(nextTs, Seq((sponsor, ENOUGH_AMT), (sender, ENOUGH_AMT))))

          def appendTx(tx:Transaction) = {
            val block = TestBlock.create(
              nextTs,
              d.lastBlockId,
              Seq(tx)
            )
            d.appendBlock(block)
            block.uniqueId
          }

          withClue("rollback sponsorship") {
            val prev = d.lastBlockId
            appendTx(tx)
            d.blockchainUpdater.sponsorOf(sender) shouldBe Some(sponsor.toAddress)
            d.removeAfter(prev)
            d.blockchainUpdater.sponsorOf(sender) shouldBe None
          }
          withClue("rollback sponsorship cancel") {
            appendTx(tx)
            d.blockchainUpdater.sponsorOf(sender) shouldBe Some(sponsor.toAddress)
            val prev = d.lastBlockId
            appendTx(tx2)
            d.blockchainUpdater.sponsorOf(sender) shouldBe None
            d.removeAfter(prev)
            d.blockchainUpdater.sponsorOf(sender) shouldBe Some(sponsor.toAddress)
          }
        }
    }



    "address script" in pendingUntilFixed(forAll(accountGen, positiveLongGen, scriptGen) {
      case (sender, initialBalance, script) =>
        withDomain() {
          d =>
            d.appendBlock(genesisBlock(nextTs, sender, initialBalance))
            val genesisBlockId = d.lastBlockId

            d.blockchainUpdater.accountScript(sender) shouldBe 'empty
            d.appendBlock(
              TestBlock.create(
                nextTs,
                genesisBlockId,
                Seq(SetScriptTransaction.selfSigned(1, sender, Some(script), 1, nextTs).explicitGet())
              ))

            val blockWithScriptId = d.lastBlockId

            d.blockchainUpdater.accountScript(sender) should contain(script)

            d.appendBlock(
              TestBlock.create(
                nextTs,
                genesisBlockId,
                Seq(SetScriptTransaction.selfSigned(1, sender, None, 1, nextTs).explicitGet())
              ))

            d.blockchainUpdater.accountScript(sender) shouldBe 'empty

            d.removeAfter(blockWithScriptId)
            d.blockchainUpdater.accountScript(sender) should contain(script)

            d.removeAfter(genesisBlockId)
            d.blockchainUpdater.accountScript(sender) shouldBe 'empty
        }
    })

    def createSettings(preActivatedFeatures: (BlockchainFeature, Int)*): FunctionalitySettings =
      TestFunctionalitySettings.Enabled
        .copy(
          preActivatedFeatures = preActivatedFeatures.map { case (k, v) => k.id -> v }(collection.breakOut),
          blocksForFeatureActivation = 1,
          featureCheckBlocksPeriod = 1
        )
  }
}
