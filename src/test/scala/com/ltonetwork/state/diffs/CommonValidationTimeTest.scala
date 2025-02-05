package com.ltonetwork.state.diffs

import com.ltonetwork.db.WithState
import com.ltonetwork.settings.TestFunctionalitySettings
import com.ltonetwork.state._
import com.ltonetwork.{NoShrink, TransactionGen}
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec

class CommonValidationTimeTest
    extends AnyPropSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with TransactionGen
    with NoShrink
    with WithState {

  property("disallows too old transacions") {
    forAll(for {
      prevBlockTs <- timestampGen
      master      <- accountGen
      height      <- positiveIntGen
      recipient   <- accountGen
      amount      <- positiveLongGen
      fee         <- smallFeeGen
      transfer1 = createLtoTransfer(master, recipient, amount, fee, prevBlockTs - CommonValidation.MaxTimePrevBlockOverTransactionDiff.toMillis - 1)
        .explicitGet()
    } yield (prevBlockTs, height, transfer1)) {
      case (prevBlockTs, height, transfer1) =>
        withStateAndHistory(TestFunctionalitySettings.Enabled) { blockchain: Blockchain =>
          TransactionDiffer(TestFunctionalitySettings.Enabled, Some(prevBlockTs), prevBlockTs, height)(blockchain, transfer1) should produce(
            "too old")
        }
    }
  }

  property("disallows transactions from far future") {
    forAll(for {
      prevBlockTs <- timestampGen
      blockTs     <- Gen.choose(prevBlockTs, prevBlockTs + 7 * 24 * 3600 * 1000)
      master      <- accountGen
      height      <- positiveIntGen
      recipient   <- accountGen
      amount      <- positiveLongGen
      fee         <- smallFeeGen
      transfer1 = createLtoTransfer(master, recipient, amount, fee, blockTs + CommonValidation.MaxTimeTransactionOverBlockDiff.toMillis + 1)
        .explicitGet()
    } yield (prevBlockTs, blockTs, height, transfer1)) {
      case (prevBlockTs, blockTs, height, transfer1) =>
        val functionalitySettings = TestFunctionalitySettings.Enabled
        withStateAndHistory(functionalitySettings) { blockchain: Blockchain =>
          TransactionDiffer(functionalitySettings, Some(prevBlockTs), blockTs, height)(blockchain, transfer1) should produce("far future")
        }
    }
  }
}
