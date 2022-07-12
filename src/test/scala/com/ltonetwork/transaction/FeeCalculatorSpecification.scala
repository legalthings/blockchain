package com.ltonetwork.transaction

import com.ltonetwork.TransactionGen
import com.ltonetwork.account.Address
import com.ltonetwork.features.BlockchainFeatures
import com.ltonetwork.fee.FeeCalculator
import com.ltonetwork.state._
import com.ltonetwork.transaction.association.{AssociationTransaction, IssueAssociationTransaction, RevokeAssociationTransaction}
import com.ltonetwork.transaction.lease.{CancelLeaseTransaction, LeaseTransaction}
import com.ltonetwork.transaction.smart.script.Script
import com.ltonetwork.transaction.sponsorship.{CancelSponsorshipTransaction, SponsorshipTransaction, SponsorshipTransactionBase}
import com.ltonetwork.transaction.transfer._
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import com.ltonetwork.utils._

class FeeCalculatorSpecification extends AnyPropSpec with ScalaCheckDrivenPropertyChecks with Matchers with TransactionGen with MockFactory {

  implicit class ConditionalAssert(v: Either[_, _]) {

    def shouldBeRightIf(cond: Boolean): Assertion = {
      if (cond) {
        v shouldBe an[Right[_, _]]
      } else {
        v shouldBe an[Left[_, _]]
      }
    }
  }

  property("Transfer transaction") {
    val feeCalc = new FeeCalculator(noScriptBlockchain)
    forAll(transferGen, Gen.choose(0.8.lto, 1.2.lto)) { (tx: TransferTransaction, fee: Long) =>
      feeCalc.enoughFee(tx.copy(fee = fee)) shouldBeRightIf (fee >= 1.lto)
    }
  }

  property("Mass Transfer transaction") {
    val feeCalc = new FeeCalculator(noScriptBlockchain)
    forAll(massTransferGen(4), Gen.choose(0.8.lto, 2.lto)) { (tx: MassTransferTransaction, fee: Long) =>
      feeCalc.enoughFee(tx.copy(fee = fee)) shouldBeRightIf (fee >= 1.lto + (tx.transfers.size * 0.1.lto))
    }
  }

  property("Lease transaction") {
    val feeCalc = new FeeCalculator(noScriptBlockchain)
    forAll(leaseGen, Gen.choose(0.8.lto, 1.2.lto)) { (tx: LeaseTransaction, fee: Long) =>
      feeCalc.enoughFee(tx.copy(fee = fee)) shouldBeRightIf (fee >= 1.lto)
    }
  }

  property("Association transaction") {
    val feeCalc = new FeeCalculator(noScriptBlockchain)
    forAll(assocTransactionGen, Gen.choose(0.4.lto, 0.6.lto)) { (tx: AssociationTransaction, fee: Long) =>
      val (txWithFee, dataLength) = tx match {
        case iatx: IssueAssociationTransaction => (iatx.copy(fee = fee), iatx.data.map(_.toBytes.length).sum)
        case ratx: RevokeAssociationTransaction => (ratx.copy(fee = fee), 0)
      }
      feeCalc.enoughFee(txWithFee) shouldBeRightIf (fee >= 0.5.lto + Math.ceil(dataLength / 256.0).toInt * 0.1.lto)
    }
  }

  property("Sponsorship transaction") {
    val feeCalc = new FeeCalculator(noScriptBlockchain)
    forAll(sponsorshipGen, Gen.choose(4.lto, 6.lto)) { (tx: SponsorshipTransaction, fee: Long) =>
      feeCalc.enoughFee(tx.copy(fee = fee)) shouldBeRightIf (fee >= 5.lto)
    }
  }

  property("Cancel Sponsorship transaction") {
    val feeCalc = new FeeCalculator(noScriptBlockchain)
    forAll(cancelSponsorshipGen, Gen.choose(0.8.lto, 1.2.lto)) { (tx: CancelSponsorshipTransaction, fee: Long) =>
      feeCalc.enoughFee(tx.copy(fee = fee)) shouldBeRightIf (fee >= 1.lto)
    }
  }

  property("Lease cancel transaction") {
    val feeCalc = new FeeCalculator(noScriptBlockchain)
    forAll(cancelLeaseGen, Gen.choose(1.lto, 1.2.lto)) { (tx: CancelLeaseTransaction, fee: Long) =>
      feeCalc.enoughFee(tx.copy(fee = fee)) shouldBeRightIf (fee >= 1.lto)
    }
  }

  property("Data transaction") {
    val feeCalc = new FeeCalculator(noScriptBlockchain)
    forAll(dataTransactionGen(10), Gen.choose(0.4.lto, 0.6.lto)) { (tx, fee: Long) =>
      feeCalc.enoughFee(tx.copy(fee = fee)) shouldBeRightIf (fee >= 0.5.lto + Math.ceil(tx.data.map(_.toBytes.length).sum / 256.0).toInt * 0.1.lto)
    }
  }

  private def createBlockchain(accountScript: Address => Option[Script]): Blockchain = {
    val r = stub[Blockchain]
    (r.accountScript _).when(*).onCall((addr: Address) => accountScript(addr)).anyNumberOfTimes()
    (r.activatedFeatures _).when().returns(Map(
      BlockchainFeatures.Juicy.id -> 0,
      BlockchainFeatures.Cobalt.id -> 0,
    )).anyNumberOfTimes()
    (r.feePrice(_: Int)).when(*).returns(100000).anyNumberOfTimes()
    r
  }

  private def noScriptBlockchain: Blockchain = createBlockchain(_ => None)
}
