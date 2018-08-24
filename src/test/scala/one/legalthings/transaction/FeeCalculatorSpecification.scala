package one.legalthings.transaction

import com.typesafe.config.ConfigFactory
import one.legalthings.TransactionGen
import one.legalthings.settings.FeesSettings
import one.legalthings.state.{ByteStr, _}
import org.scalamock.scalatest.MockFactory
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Assertion, Matchers, PropSpec}
import one.legalthings.account.{Address, PrivateKeyAccount}
import one.legalthings.transaction.assets._
import one.legalthings.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import one.legalthings.transaction.smart.script.Script
import one.legalthings.transaction.transfer._

class FeeCalculatorSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen with MockFactory {

  private val configString =
    """lto {
      |  fees {
      |    transfer {
      |      WAVES = 100000
      |      "JAudr64y6YxTgLn9T5giKKqWGkbMfzhdRAxmNNfn6FJN" = 2
      |    }
      |    lease {
      |      WAVES = 400000
      |    }
      |    lease-cancel {
      |      WAVES = 500000
      |    }
      |    data {
      |      WAVES = 100000
      |    }
      |  }
      |}""".stripMargin

  private val config = ConfigFactory.parseString(configString)

  private val mySettings = FeesSettings.fromConfig(config)

  private val WhitelistedAsset = ByteStr.decodeBase58("JAudr64y6YxTgLn9T5giKKqWGkbMfzhdRAxmNNfn6FJN").get

  implicit class ConditionalAssert(v: Either[_, _]) {

    def shouldBeRightIf(cond: Boolean): Assertion = {
      if (cond) {
        v shouldBe an[Right[_, _]]
      } else {
        v shouldBe an[Left[_, _]]
      }
    }
  }

  property("Transfer transaction ") {
    val feeCalc = new FeeCalculator(mySettings, noScriptBlockchain)
    forAll(transferV1Gen) { tx: TransferTransactionV1 =>
      feeCalc.enoughFee(tx) shouldBeRightIf (tx.fee >= 100000)
    }
  }

  property("Lease transaction") {
    val feeCalc = new FeeCalculator(mySettings, noScriptBlockchain)
    forAll(leaseGen) { tx: LeaseTransaction =>
      feeCalc.enoughFee(tx) shouldBeRightIf (tx.fee >= 400000)
    }
  }

  property("Lease cancel transaction") {
    val feeCalc = new FeeCalculator(mySettings, noScriptBlockchain)
    forAll(leaseCancelGen) { tx: LeaseCancelTransaction =>
      feeCalc.enoughFee(tx) shouldBeRightIf (tx.fee >= 500000)
    }
  }

  property("Data transaction") {
    val feeCalc = new FeeCalculator(mySettings, noScriptBlockchain)
    forAll(dataTransactionGen) { tx =>
      feeCalc.enoughFee(tx) shouldBeRightIf (tx.fee >= Math.ceil(tx.bytes().length / 1024.0) * 100000)
    }
  }

  private def createBlockchain(accountScript: Address => Option[Script]): Blockchain = {
    val r = stub[Blockchain]
    (r.accountScript _).when(*).onCall((addr: Address) => accountScript(addr)).anyNumberOfTimes()
    r
  }

  private def noScriptBlockchain: Blockchain = createBlockchain(_ => None)
}
