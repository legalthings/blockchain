package com.wavesplatform.state.diffs

import cats._
import com.wavesplatform.state._
import com.wavesplatform.{NoShrink, TransactionGen}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import com.wavesplatform.account.Address
import com.wavesplatform.settings.TestFunctionalitySettings
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.transaction.GenesisTransaction
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer._

class LeaseTransactionsDiffTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with NoShrink {

  private val settings =
    TestFunctionalitySettings.Enabled

  def total(l: LeaseBalance): Long = l.in - l.out

  property("can lease/cancel lease preserving waves invariant") {

    val sunnyDayLeaseLeaseCancel: Gen[(GenesisTransaction, LeaseTransaction, LeaseCancelTransaction)] = for {
      master    <- accountGen
      recipient <- accountGen suchThat (_ != master)
      ts        <- positiveIntGen
      genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
      (lease, unlease) <- leaseAndCancelGeneratorP(master, recipient, master, ts)
    } yield (genesis, lease, unlease)

    forAll(sunnyDayLeaseLeaseCancel) {
      case ((genesis, lease, leaseCancel)) =>
        assertDiffAndState(Seq(TestBlock.create(Seq(genesis))), TestBlock.create(Seq(lease))) {
          case (totalDiff, newState) =>
            val totalPortfolioDiff = Monoid.combineAll(totalDiff.portfolios.values)
//            totalPortfolioDiff.balance shouldBe 0
            total(totalPortfolioDiff.lease) shouldBe 0
//            totalPortfolioDiff.effectiveBalance shouldBe 0
        }

        assertDiffAndState(Seq(TestBlock.create(Seq(genesis, lease))), TestBlock.create(Seq(leaseCancel))) {
          case (totalDiff, newState) =>
            val totalPortfolioDiff = Monoid.combineAll(totalDiff.portfolios.values)
//            totalPortfolioDiff.balance shouldBe 0
            total(totalPortfolioDiff.lease) shouldBe 0
//            totalPortfolioDiff.effectiveBalance shouldBe 0
        }
    }
  }

  val cancelLeaseTwice: Gen[(GenesisTransaction, TransferTransactionV1, LeaseTransaction, LeaseCancelTransaction, LeaseCancelTransaction)] = for {
    master   <- accountGen
    recpient <- accountGen suchThat (_ != master)
    ts       <- timestampGen
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
    (lease, unlease) <- leaseAndCancelGeneratorP(master, recpient, master, ts)
    fee2             <- smallFeeGen
    unlease2         <- createLeaseCancel(master, lease.id(), fee2, ts + 1)
    // ensure recipient has enough effective balance
    payment <- wavesTransferGeneratorP(master, recpient) suchThat (_.amount > lease.amount)
  } yield (genesis, payment, lease, unlease, unlease2)

  property("cannot cancel lease twice after allowMultipleLeaseCancelTransactionUntilTimestamp") {
    forAll(cancelLeaseTwice) {
      case ((genesis, payment, lease, leaseCancel, leaseCancel2)) =>
        assertDiffEi(Seq(TestBlock.create(Seq(genesis, payment, lease, leaseCancel))), TestBlock.create(Seq(leaseCancel2)), settings) {
          totalDiffEi =>
            totalDiffEi should produce("Cannot cancel already cancelled lease")
        }
    }
  }

  property("cannot lease more than actual balance(cannot lease forward)") {
    val setup: Gen[(GenesisTransaction, LeaseTransaction, LeaseTransaction)] = for {
      master    <- accountGen
      recipient <- accountGen suchThat (_ != master)
      forward   <- accountGen suchThat (!Set(master, recipient).contains(_))
      ts        <- positiveIntGen
      genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
      (lease, _)        <- leaseAndCancelGeneratorP(master, recipient, master, ts)
      (leaseForward, _) <- leaseAndCancelGeneratorP(recipient, forward, recipient, ts)
    } yield (genesis, lease, leaseForward)

    forAll(setup) {
      case ((genesis, lease, leaseForward)) =>
        assertDiffEi(Seq(TestBlock.create(Seq(genesis, lease))), TestBlock.create(Seq(leaseForward)), settings) { totalDiffEi =>
          totalDiffEi should produce("Cannot lease more than own")
        }
    }
  }

  def cancelLeaseOfAnotherSender(
      unleaseByRecipient: Boolean): Gen[(GenesisTransaction, GenesisTransaction, LeaseTransaction, LeaseCancelTransaction)] =
    for {
      master    <- accountGen
      recipient <- accountGen suchThat (_ != master)
      other     <- accountGen suchThat (_ != recipient)
      unleaser = if (unleaseByRecipient) recipient else other
      ts <- timestampGen
      genesis: GenesisTransaction  = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
      genesis2: GenesisTransaction = GenesisTransaction.create(unleaser, ENOUGH_AMT, ts).explicitGet()
      (lease, _)              <- leaseAndCancelGeneratorP(master, recipient, master, ts)
      fee2                    <- smallFeeGen
      unleaseOtherOrRecipient <- createLeaseCancel(unleaser, lease.id(), fee2, ts + 1)
    } yield (genesis, genesis2, lease, unleaseOtherOrRecipient)

  property("cannot cancel lease of another sender") {
    forAll(Gen.oneOf(true, false).flatMap(cancelLeaseOfAnotherSender)) {
      case ((genesis, genesis2, lease, unleaseOtherOrRecipient)) =>
        assertDiffEi(Seq(TestBlock.create(Seq(genesis, genesis2, lease))), TestBlock.create(Seq(unleaseOtherOrRecipient)), settings) {
          totalDiffEi =>
            totalDiffEi should produce("LeaseTransaction was leased by other sender")
        }
    }
  }
}
