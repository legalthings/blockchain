package com.wavesplatform.state.diffs

import com.wavesplatform.account.{Address, PrivateKeyAccount}
import com.wavesplatform.block.Block
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.lagonaki.mocks.TestBlock.{create => block}
import com.wavesplatform.state.EitherExt2
import com.wavesplatform.transaction.transfer.TransferTransactionV2
import com.wavesplatform.transaction.{GenesisTransaction, SponsorshipTransaction}
import com.wavesplatform.{NoShrink, TransactionGen, WithDB}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}

class SponsorTransactionDiffTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with NoShrink with WithDB {

  val baseSetup: Gen[(GenesisTransaction, PrivateKeyAccount, Long)] = for {
    master <- accountGen
    ts     <- positiveLongGen
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
  } yield (genesis, master, ts)

  property("sunny day") {
    val setup = for {
      sponsor <- accountGen
      sender  <- accountGen
      other   <- accountGen
      ts      <- timestampGen
      sposorTxFee = 5 * 100000000L
      transferTxFee <- enoughFeeGen
      transferAmt   <- positiveLongGen
      g1 = GenesisTransaction.create(sponsor, ENOUGH_AMT, ts).explicitGet()
      g2 = GenesisTransaction.create(sender, ENOUGH_AMT, ts).explicitGet()

      version <- Gen.oneOf(SponsorshipTransaction.supportedVersions.toSeq)
      tx0 = SponsorshipTransaction.selfSigned(version, sponsor, sender, sposorTxFee, ts + 1).explicitGet()
      tx1 = TransferTransactionV2.selfSigned(2, sender, other, transferAmt, ts + 1, transferTxFee, Array.emptyByteArray).explicitGet()
    } yield (List(g1, g2), tx0, tx1)

    forAll(setup) {
      case (genesis, sponsorship, transfer) =>
        assertDiffAndState(Seq(block(genesis), block(Seq(sponsorship))), block(Seq(transfer))) {
          case (d, b) =>
            d.portfolios(sponsorship.sender.toAddress).balance shouldBe (-transfer.fee)
            d.portfolios(transfer.sender.toAddress).balance shouldBe (-transfer.amount)
            d.portfolios(transfer.recipient.asInstanceOf[Address]).balance shouldBe (transfer.amount)
            val fees = Block.CurrentBlockFeePart(transfer.fee) + sponsorship.fee - Block.CurrentBlockFeePart(sponsorship.fee)
            d.portfolios(TestBlock.defaultSigner).balance shouldBe fees
            b.sponsorOf(transfer.sender.toAddress) shouldBe Some(sponsorship.sender.toAddress)
        }
    }
  }
}
