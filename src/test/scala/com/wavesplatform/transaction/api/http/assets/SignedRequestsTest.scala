package com.wavesplatform.transaction.api.http.assets

import com.wavesplatform.state.EitherExt2
import com.wavesplatform.utils.Base58
import org.scalatest.{FunSuite, Matchers}
import play.api.libs.json.Json
import com.wavesplatform.api.http.assets._

class SignedRequestsTest extends FunSuite with Matchers {

  test("AssetIssueRequest json parsing works") {
    val json =
      """
        {
          "name": "string",
          "quantity": 100000,
          "timestamp": 1484064349669,
          "description": "string",
          "signature": "d3JTwzxHj3j74yemdvw2xAdFeMo6hDJ9i2s3v3e4GMrz2Q25G89Pp3HyXfKk3mwNcKWjtyoTWvTt1eLk2KzetoL",
          "senderPublicKey": "D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5",
          "decimals": 2,
          "reissuable": true,
          "fee": 100000
        }
      """
    val req = Json.parse(json).validate[SignedIssueV1Request].get
    req.name shouldBe "string"
    req.quantity shouldBe 100000L
    req.fee shouldBe 100000L
    req.decimals shouldBe 2
    req.timestamp shouldBe 1484064349669L
    req.reissuable shouldBe true

    val tx = req.toTx.explicitGet()
    Base58.encode(tx.name) shouldBe "zVbyBrMk"
    Base58.encode(tx.description) shouldBe "zVbyBrMk"
    tx.reissuable shouldBe true
    tx.decimals shouldBe 2
    tx.fee shouldBe 100000L
    tx.quantity shouldBe 100000L
    tx.timestamp shouldBe 1484064349669L
    tx.signature.base58 shouldBe "d3JTwzxHj3j74yemdvw2xAdFeMo6hDJ9i2s3v3e4GMrz2Q25G89Pp3HyXfKk3mwNcKWjtyoTWvTt1eLk2KzetoL"
  }

  test("AssetReissueRequest json parsing works") {
    val json =
      """
        |{
        |"senderPublicKey":"D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5",
        |"quantity":100000,"reissuable":true,
        |"assetId":"",
        |"fee":100000,"timestamp":1234,
        |"reissuable":true,
        |"signature":"4YWbtkDA7PHH1MCxEUaP12pkNRPNqpJh8X7aagZzLyDNbzgopXJb7NHNNV8rjXcy2WsAKX1wzti7Bishu8u6hwtF"
        |}
      """.stripMargin
    val req = Json.parse(json).validate[SignedReissueV1Request].get
    req.signature shouldBe "4YWbtkDA7PHH1MCxEUaP12pkNRPNqpJh8X7aagZzLyDNbzgopXJb7NHNNV8rjXcy2WsAKX1wzti7Bishu8u6hwtF"
    req.fee shouldBe 100000L
    req.quantity shouldBe 100000L
    req.timestamp shouldBe 1234L
    req.reissuable shouldBe true

    val tx = req.toTx.explicitGet()
    tx.assetId.base58 shouldBe "Ha35nwsnmYxHRF8UmKG3S523BycBLZFU4FZnjXryKd4L"
    tx.reissuable shouldBe true
    tx.fee shouldBe 100000L
    tx.quantity shouldBe 100000L
    tx.timestamp shouldBe 1234L
    tx.reissuable shouldBe true
    tx.signature.base58 shouldBe "4YWbtkDA7PHH1MCxEUaP12pkNRPNqpJh8X7aagZzLyDNbzgopXJb7NHNNV8rjXcy2WsAKX1wzti7Bishu8u6hwtF"
  }

  test("AssetTransfer json parsing works") {
    val json =
      """
        |{
        |   "recipient":"3Mr31XDsqdktAdNQCdSd8ieQuYoJfsnLVFg",
        |   "timestamp":1479462208828,
        |   "amount":100000,
        |   "fee":100000,
        |   "senderPublicKey":"D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5",
        |   "signature":"4dPRTW6XyRQUTQwwpuZDCNy1UDHYG9WGsEQnn5v49Lj5uyh4XGDdwtEq3t6ZottweAXHieK32UokHwiTxGFtz9bQ",
        |   "attachment":"A"
        |}
      """.stripMargin
    val req = Json.parse(json).validate[SignedTransferV1Request].get
    req.recipient shouldBe "3Mr31XDsqdktAdNQCdSd8ieQuYoJfsnLVFg"
    req.timestamp shouldBe 1479462208828L
    req.amount shouldBe 100000
    req.fee shouldBe 100000
    req.senderPublicKey shouldBe "D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5"
    req.signature shouldBe "4dPRTW6XyRQUTQwwpuZDCNy1UDHYG9WGsEQnn5v49Lj5uyh4XGDdwtEq3t6ZottweAXHieK32UokHwiTxGFtz9bQ"
    req.attachment shouldBe Some("A")

    val tx = req.toTx.explicitGet()
    Base58.encode(tx.sender.publicKey) shouldBe "D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5"
    tx.timestamp shouldBe 1479462208828L
    tx.attachment shouldBe Base58.decode("A").get
    tx.amount shouldBe 100000
    tx.fee shouldBe 100000
    tx.signature.base58 shouldBe "4dPRTW6XyRQUTQwwpuZDCNy1UDHYG9WGsEQnn5v49Lj5uyh4XGDdwtEq3t6ZottweAXHieK32UokHwiTxGFtz9bQ"
  }

  test("AssetTransfer with a fee in an asset json parsing works") {
    val json =
      """
        |{
        |   "senderPublicKey":"FJuErRxhV9JaFUwcYLabFK5ENvDRfyJbRz8FeVfYpBLn",
        |   "recipient":"3N5XyVTp4kEARUGRkQTuCVN6XjV4c5iwcJt",
        |   "timestamp":1489054107569,
        |   "amount":1000,
        |   "fee":100,
        |   "signature":"UAhYXYdkFAFBuwAuUFP3yw7E8aRTyx56ZL4UPbT4ufomBzVLMRpdW2dCtJmfpCuPPMhGTvdzhXwb7o4ER6HAUpJ",
        |   "attachment":"2Kk7Zsr1e9jsqSBM5hpF"
        |}
      """.stripMargin
    val req = Json.parse(json).validate[SignedTransferV1Request].get
    req.recipient shouldBe "3N5XyVTp4kEARUGRkQTuCVN6XjV4c5iwcJt"
    req.timestamp shouldBe 1489054107569L
    req.amount shouldBe 1000
    req.fee shouldBe 100
    req.senderPublicKey shouldBe "FJuErRxhV9JaFUwcYLabFK5ENvDRfyJbRz8FeVfYpBLn"
    req.signature shouldBe "UAhYXYdkFAFBuwAuUFP3yw7E8aRTyx56ZL4UPbT4ufomBzVLMRpdW2dCtJmfpCuPPMhGTvdzhXwb7o4ER6HAUpJ"
    req.attachment shouldBe Some("2Kk7Zsr1e9jsqSBM5hpF")

    val tx = req.toTx.explicitGet()
    Base58.encode(tx.sender.publicKey) shouldBe "FJuErRxhV9JaFUwcYLabFK5ENvDRfyJbRz8FeVfYpBLn"
    tx.timestamp shouldBe 1489054107569L
    tx.attachment shouldBe Base58.decode("2Kk7Zsr1e9jsqSBM5hpF").get
    tx.amount shouldBe 1000
    tx.fee shouldBe 100
    tx.signature.base58 shouldBe "UAhYXYdkFAFBuwAuUFP3yw7E8aRTyx56ZL4UPbT4ufomBzVLMRpdW2dCtJmfpCuPPMhGTvdzhXwb7o4ER6HAUpJ"
  }

  test("AssetBurnRequest json parsing works") {
    val json =
      """
        |{
        |"senderPublicKey":"D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5",
        |"assetId":"D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5",
        |"quantity":10000,
        |"fee":100000000,"timestamp":1477302582842,
        |"signature":"H3F8gAsKYeJAPmxCagLaCHycqkr8KiYvzJ4dhophZs31Unmg3dLwVK5k1v1M2Z5zLuQySthpf3DeEyhL6cdpbqp"
        |}
      """.stripMargin
    val req = Json.parse(json).validate[SignedBurnV1Request].get
    req.senderPublicKey shouldBe "D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5"
    req.signature shouldBe "H3F8gAsKYeJAPmxCagLaCHycqkr8KiYvzJ4dhophZs31Unmg3dLwVK5k1v1M2Z5zLuQySthpf3DeEyhL6cdpbqp"
    req.fee shouldBe 100000000L
    req.quantity shouldBe 10000
  }

}
