package com.wavesplatform.transaction

import com.wavesplatform.TransactionGen
import com.wavesplatform.account.{PrivateKeyAccount, PublicKeyAccount}
import com.wavesplatform.api.http.SignedRevokeAssociationRequest
import com.wavesplatform.state.{ByteStr, EitherExt2}
import com.wavesplatform.utils.Base58
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import play.api.libs.json.{Format, Json}

class AssociationTransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  private def checkSerialization(tx: IssueAssociationTransaction): Assertion = {
    val parsed = IssueAssociationTransaction.parseBytes(tx.bytes()).get

    parsed.sender.address shouldEqual tx.sender.address
    parsed.timestamp shouldEqual tx.timestamp
    parsed.fee shouldEqual tx.fee
    parsed.assoc shouldEqual tx.assoc

    parsed.bytes() shouldEqual tx.bytes()
  }

  property("serialization roundtrip") {
    forAll(assocTransactionGen suchThat(_.isInstanceOf[IssueAssociationTransaction]) map (_.asInstanceOf[IssueAssociationTransaction]))(checkSerialization)
  }

  property("serialization from TypedTransaction") {
    forAll(assocTransactionGen) { tx: AssociationTransactionBase =>
      val recovered = IssueAssociationTransaction.parseBytes(tx.bytes()).get
      recovered.bytes() shouldEqual tx.bytes()
    }
  }

  property("JSON roundtrip") {
    implicit val signedFormat: Format[SignedRevokeAssociationRequest] = Json.format[SignedRevokeAssociationRequest]

    forAll(assocTransactionGen) { tx =>
      val json = tx.json()
      json.toString shouldEqual tx.toString

      val req = json.as[SignedRevokeAssociationRequest]
      req.senderPublicKey shouldEqual Base58.encode(tx.sender.publicKey)
      req.fee shouldEqual tx.fee
      req.timestamp shouldEqual tx.timestamp
      req.associationType shouldEqual tx.assoc.assocType
      req.party shouldEqual tx.assoc.party.toString
      if (tx.assoc.hash.isDefined)
        req.hash shouldEqual tx.assoc.hash.get.base58
    }
  }

  property(testName = "JSON format validation") {
    val p  = PrivateKeyAccount.fromSeed("xxx").explicitGet().toAddress
    val js = Json.parse(s"""{
                       "type": 16,
                       "id": "EztEhGMm34TKTj3zLWwFJ4HGyfhuUTwVPceqCzR1Qeuf",
                       "sender": "3Mr31XDsqdktAdNQCdSd8ieQuYoJfsnLVFg",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000,
                       "timestamp": 1526911531530,
                       "proofs": [
                       "32mNYSefBTrkVngG5REkmmGAVv69ZvNhpbegmnqDReMTmXNyYqbECPgHgXrX2UwyKGLFS45j7xDFyPXjF8jcfw94"
                       ],
                       "version": 1,
                       "party" : "$p",
                       "associationType" : 420,
                       "hash" : ""
                       }
  """)

    val arr = ByteStr.decodeBase58("32mNYSefBTrkVngG5REkmmGAVv69ZvNhpbegmnqDReMTmXNyYqbECPgHgXrX2UwyKGLFS45j7xDFyPXjF8jcfw94").get
    val tx = IssueAssociationTransaction
      .create(
        version = 1,
        sender = PublicKeyAccount.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        party = p,
        assocType = 420,
        hash = None,
        feeAmount = 100000,
        timestamp = 1526911531530L,
        proofs = Proofs(Seq(arr))
      )
      .explicitGet()

    js shouldEqual tx.json()
  }

}
