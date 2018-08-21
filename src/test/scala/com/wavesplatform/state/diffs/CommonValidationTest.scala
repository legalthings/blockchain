package com.wavesplatform.state.diffs

import com.wavesplatform.db.WithState
import com.wavesplatform.features.{BlockchainFeature, BlockchainFeatures}
import com.wavesplatform.lang.v1.compiler.Terms._
import com.wavesplatform.mining.MiningConstraint
import com.wavesplatform.settings.{Constants, FunctionalitySettings, TestFunctionalitySettings}
import com.wavesplatform.state.EitherExt2
import com.wavesplatform.{NoShrink, TransactionGen}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Assertion, Matchers, PropSpec}
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.transaction.assets.{IssueTransactionV1, IssueTransactionV2, SponsorFeeTransaction}
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.smart.script.v1.ScriptV1
import com.wavesplatform.transaction.transfer._
import com.wavesplatform.transaction.{GenesisTransaction, Transaction, ValidationError}

class CommonValidationTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with WithState with NoShrink {

  property("disallows double spending") {
    val preconditionsAndPayment: Gen[(GenesisTransaction, TransferTransactionV1)] = for {
      master    <- accountGen
      recipient <- otherAccountGen(candidate = master)
      ts        <- positiveIntGen
      genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
      transfer: TransferTransactionV1 <- wavesTransferGeneratorP(master, recipient)
    } yield (genesis, transfer)

    forAll(preconditionsAndPayment) {
      case (genesis, transfer) =>
        assertDiffEi(Seq(TestBlock.create(Seq(genesis, transfer))), TestBlock.create(Seq(transfer))) { blockDiffEi =>
          blockDiffEi should produce("AlreadyInTheState")
        }

        assertDiffEi(Seq(TestBlock.create(Seq(genesis))), TestBlock.create(Seq(transfer, transfer))) { blockDiffEi =>
          blockDiffEi should produce("AlreadyInTheState")
        }
    }
  }

  private def sponsoredTransactionsCheckFeeTest(feeInAssets: Boolean, feeAmount: Long)(f: Either[ValidationError, Unit] => Assertion): Unit = {
    val settings = createSettings(BlockchainFeatures.FeeSponsorship -> 0)
    val gen      = sponsorAndSetScriptGen(sponsorship = true, smartToken = false, smartAccount = false, feeInAssets, feeAmount)
    forAll(gen) {
      case (genesisBlock, transferTx) =>
        withStateAndHistory(settings) { blockchain =>
          val preconditionDiff = BlockDiffer.fromBlock(settings, blockchain, None, genesisBlock, MiningConstraint.Unlimited).explicitGet()._1
          blockchain.append(preconditionDiff, genesisBlock)

          f(CommonValidation.checkFee(blockchain, settings, 1, transferTx))
        }
    }
  }

  private def smartTokensCheckFeeTest(feeInAssets: Boolean, feeAmount: Long)(f: Either[ValidationError, Unit] => Assertion): Unit = {
    val settings = createSettings(BlockchainFeatures.SmartAccounts -> 0, BlockchainFeatures.SmartAssets -> 0)
    val gen      = sponsorAndSetScriptGen(sponsorship = false, smartToken = true, smartAccount = false, feeInAssets, feeAmount)
    forAll(gen) {
      case (genesisBlock, transferTx) =>
        withStateAndHistory(settings) { blockchain =>
          val preconditionDiff = BlockDiffer.fromBlock(settings, blockchain, None, genesisBlock, MiningConstraint.Unlimited).explicitGet()._1
          blockchain.append(preconditionDiff, genesisBlock)

          f(CommonValidation.checkFee(blockchain, settings, 1, transferTx))
        }
    }
  }

  ignore("checkFee for smart tokens sunny") {
    smartTokensCheckFeeTest(feeInAssets = false, feeAmount = 1)(_ shouldBe 'right)
  }

  private def smartAccountCheckFeeTest(feeInAssets: Boolean, feeAmount: Long)(f: Either[ValidationError, Unit] => Assertion): Unit = {
    val settings = createSettings(BlockchainFeatures.SmartAccounts -> 0)
    val gen      = sponsorAndSetScriptGen(sponsorship = false, smartToken = false, smartAccount = true, feeInAssets, feeAmount)
    forAll(gen) {
      case (genesisBlock, transferTx) =>
        withStateAndHistory(settings) { blockchain =>
          val preconditionDiff = BlockDiffer.fromBlock(settings, blockchain, None, genesisBlock, MiningConstraint.Unlimited).explicitGet()._1
          blockchain.append(preconditionDiff, genesisBlock)

          f(CommonValidation.checkFee(blockchain, settings, 1, transferTx))
        }
    }
  }

  ignore("checkFee for smart accounts sunny") {
    smartAccountCheckFeeTest(feeInAssets = false, feeAmount = 400000)(_ shouldBe 'right)
  }

  private def sponsorAndSetScriptGen(sponsorship: Boolean, smartToken: Boolean, smartAccount: Boolean, feeInAssets: Boolean, feeAmount: Long) =
    for {
      richAcc      <- accountGen
      recipientAcc <- accountGen
      ts = System.currentTimeMillis()
    } yield {
      val script = ScriptV1(TRUE).explicitGet()

      val genesisTx = GenesisTransaction.create(richAcc, ENOUGH_AMT, ts).explicitGet()

      val issueTx =
        if (smartToken)
          IssueTransactionV2
            .selfSigned(
              IssueTransactionV2.supportedVersions.head,
              AddressScheme.current.chainId,
              richAcc,
              "test".getBytes(),
              "desc".getBytes(),
              Long.MaxValue,
              2,
              reissuable = false,
              Some(script),
              Constants.UnitsInWave,
              ts
            )
            .explicitGet()
        else
          IssueTransactionV1
            .selfSigned(richAcc, "test".getBytes(), "desc".getBytes(), Long.MaxValue, 2, reissuable = false, Constants.UnitsInWave, ts)
            .explicitGet()

      val transferWavesTx = TransferTransactionV1
        .selfSigned(richAcc, recipientAcc, 10 * Constants.UnitsInWave, ts, 1 * Constants.UnitsInWave, Array.emptyByteArray)
        .explicitGet()

      val transferAssetTx = TransferTransactionV1
        .selfSigned(richAcc, recipientAcc, 100, ts, 1 * Constants.UnitsInWave, Array.emptyByteArray)
        .explicitGet()

      val sponsorTx =
        if (sponsorship)
          Seq(
            SponsorFeeTransaction
              .selfSigned(1, richAcc, issueTx.id(), Some(10), Constants.UnitsInWave, ts)
              .explicitGet()
          )
        else Seq.empty

      val setScriptTx =
        if (smartAccount)
          Seq(
            SetScriptTransaction
              .selfSigned(
                SetScriptTransaction.supportedVersions.head,
                recipientAcc,
                Some(script),
                1 * Constants.UnitsInWave,
                ts
              )
              .explicitGet()
          )
        else Seq.empty

      val transferBackTx = TransferTransactionV1
        .selfSigned(
          recipientAcc,
          richAcc,
          1,
          ts,
          feeAmount,
          Array.emptyByteArray
        )
        .explicitGet()

      (TestBlock.create(Vector[Transaction](genesisTx, issueTx, transferWavesTx, transferAssetTx) ++ sponsorTx ++ setScriptTx), transferBackTx)
    }

  private def createSettings(preActivatedFeatures: (BlockchainFeature, Int)*): FunctionalitySettings =
    TestFunctionalitySettings.Enabled
      .copy(
        preActivatedFeatures = preActivatedFeatures.map { case (k, v) => k.id -> v }(collection.breakOut),
        blocksForFeatureActivation = 1,
        featureCheckBlocksPeriod = 1
      )

}
