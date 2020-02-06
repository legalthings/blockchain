package com.wavesplatform.network

import java.nio.charset.StandardCharsets

import com.wavesplatform.TransactionGen
import com.wavesplatform.transaction.transfer.TransferTransactionV1
import com.wavesplatform.transaction.{SignedTransaction, Transaction}
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import org.scalamock.scalatest.MockFactory
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FreeSpec, Matchers}

class MessageCodecSpec extends FreeSpec with Matchers with MockFactory with PropertyChecks with TransactionGen {

  "should block a sender of invalid messages" in {
    val codec = new SpiedMessageCodec
    val ch    = new EmbeddedChannel(codec)

    ch.writeInbound(RawBytes(TransactionSpec.messageCode, "foo".getBytes(StandardCharsets.UTF_8)))
    ch.readInbound[TransferTransactionV1]()

    codec.blockCalls shouldBe 1
  }

  "should not block a sender of valid messages" in forAll(randomTransactionGen) { origTx: SignedTransaction =>
    val codec = new SpiedMessageCodec
    val ch    = new EmbeddedChannel(codec)

    ch.writeInbound(RawBytes.from(origTx))
    val decodedTx = ch.readInbound[Transaction]()

    decodedTx shouldBe origTx
    codec.blockCalls shouldBe 0
  }

  private class SpiedMessageCodec extends MessageCodec(PeerDatabase.NoOp) {
    var blockCalls = 0

    override def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
      blockCalls += 1
    }
  }

}
