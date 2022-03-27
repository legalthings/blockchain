package com.ltonetwork.state

import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PortfolioTest extends AnyFunSuite with Matchers {
  test("pessimistic - should return only withdraws") {
    val Seq(fooKey, barKey, bazKey) = Seq("foo", "bar", "baz").map(x => ByteStr(x.getBytes(StandardCharsets.UTF_8)))

    val orig = Portfolio(
      balance = -10,
      lease = LeaseBalance(
        in = 11,
        out = 12,
        unbonding = 13
      )
    )

    val p = orig.pessimistic
    p.balance shouldBe orig.balance
    p.lease.in shouldBe 0
    p.lease.out shouldBe orig.lease.out
    p.lease.unbonding shouldBe orig.lease.unbonding
  }

  test("pessimistic - positive balance is turned into zero") {
    val orig = Portfolio(
      balance = 10,
      lease = LeaseBalance(0, 0, 0)
    )

    val p = orig.pessimistic
    p.balance shouldBe 0
  }

}
