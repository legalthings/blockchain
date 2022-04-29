package com.ltonetwork.network

import com.google.common.primitives.{Bytes, Ints}
import com.ltonetwork.utils.Base58
import io.swagger.v3.oas.annotations.media.Schema
import play.api.libs.json._
import scorex.crypto.signatures.Curve25519._
import java.util.Objects

import scala.util.{Failure, Success}

case class BlockCheckpoint(height: Int, @Schema(`type` = "java.lang.String") signature: Array[Byte]) {
  override def equals(b: Any): Boolean = b match {
    case other: BlockCheckpoint => height == other.height && signature.sameElements(other.signature)
    case _                      => false
  }

  override def hashCode(): Int = Objects.hash(Int.box(height), signature)
}

case class Checkpoint(items: Seq[BlockCheckpoint], @Schema(`type` = "java.lang.String") signature: Array[Byte]) {
  def toSign: Array[Byte] = {
    val length      = items.size
    val lengthBytes = Ints.toByteArray(length)

    items.foldLeft(lengthBytes) {
      case (bs, BlockCheckpoint(h, s)) =>
        Bytes.concat(bs, Ints.toByteArray(h), s)
    }
  }
}

object Checkpoint {
  def historyPoints(n: Int, maxRollback: Int, resultSize: Int = MaxCheckpoints): Seq[Int] =
    mult(maxRollback, 2).map(n - _).takeWhile(_ > 0).take(resultSize)

  private def mult(start: Int, step: Int): Stream[Int] =
    Stream.cons(start, mult(start * step, step))

  val MaxCheckpoints = 10

  implicit val byteArrayReads = new Reads[Array[Byte]] {
    def reads(json: JsValue) = json match {
      case JsString(s) =>
        Base58.decode(s) match {
          case Success(bytes) if bytes.length == SignatureLength => JsSuccess(bytes)
          case Success(bytes)                                    => JsError(JsonValidationError("error.incorrect.signatureLength", bytes.length.toString))
          case Failure(t)                                        => JsError(JsonValidationError(Seq("error.incorrect.base58", t.getLocalizedMessage), s))
        }
      case _ => JsError("error.expected.jsstring")
    }
  }

  implicit val blockCheckpointFormat: Reads[BlockCheckpoint] = Json.reads
  implicit val checkpointFormat: Reads[Checkpoint]           = Json.reads
}
