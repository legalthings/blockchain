package com.ltonetwork.api

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Route
import com.ltonetwork.account.Address
import com.ltonetwork.http.BroadcastRoute
import com.ltonetwork.settings.RestAPISettings
import com.ltonetwork.state.{Blockchain, ByteStr}
import com.ltonetwork.transaction.association.{AssociationTransaction, IssueAssociationTransaction, RevokeAssociationTransaction}
import com.ltonetwork.utils.Time
import com.ltonetwork.utx.UtxPool
import com.ltonetwork.wallet.Wallet
import io.netty.channel.group.ChannelGroup
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.{Operation, Parameter, Parameters}
import jakarta.ws.rs.{GET, Path}
import play.api.libs.json._

@Path("/associations")
@Tag(name = "associations")
case class AssociationsApiRoute(settings: RestAPISettings,
                                wallet: Wallet,
                                blockchain: Blockchain,
                                utx: UtxPool,
                                allChannels: ChannelGroup,
                                time: Time)
    extends ApiRoute
    with BroadcastRoute {

  import AssociationsApiRoute._

  override lazy val route: Route =
    pathPrefix("associations") {
      associations
    }

  @GET
  @Path("/status/{address}")
  @Operation(
    summary = "Account's associations"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "address",
        description = "Wallet address",
        required = true,
        schema = new Schema(implementation = classOf[String]),
        in = ParameterIn.PATH
      )
    )
  )
  def associations: Route = (path("status" / Segment) & get) { address =>
    complete(
      Address
        .fromString(address)
        .right
        .map(acc => {
          ToResponseMarshallable(associationsJson(acc, blockchain.associations(acc)))
        })
        .getOrElse(InvalidAddress)
    )
  }

  private def associationsJson(address: Address, associations: Blockchain.Associations): AssociationsInfo = {
    def fold(list: List[(Int, AssociationTransaction)]): List[AssociationInfo] = {
      list
        .sortBy(t => (t._2.timestamp, t._2.typeId))
        .foldLeft(Map.empty[(Int, Address, Option[ByteStr]), (Int, Address, ByteStr, Option[(Int, ByteStr)])]) {
          case (acc, (height, tx: AssociationTransaction)) =>
            val cp = if (address == tx.sender.toAddress) tx.recipient else tx.sender.toAddress
            (tx, acc.get(tx.assoc)) match {
              case (_: IssueAssociationTransaction, None)                    => acc + (tx.assoc -> (height, cp, tx.id(), None))
              case (_: RevokeAssociationTransaction, Some((h, _, bs, None))) => acc + (tx.assoc -> (h, cp, bs, Some((height, tx.id()))))
              case _                                                         => acc
            }
        }
        .toList
        .sortBy(_._2._1)
        .map {
          case (assoc, (h, cp, id, r)) =>
            val (assocType, _, hash) = assoc
            AssociationInfo(
              recipient = cp.stringRepr,
              hash = hash.map(_.base58).getOrElse(""),
              associationType = assocType,
              issueHeight = h,
              issueTransactionId = id.toString,
            )
        }
    }

    AssociationsInfo(address.stringRepr, fold(associations.outgoing), fold(associations.incoming))
  }
}

object AssociationsApiRoute {

  case class AssociationInfo(recipient: String,
                             hash: String,
                             associationType: Int,
                             issueHeight: Int,
                             issueTransactionId: String,

)

  case class AssociationsInfo(address: String, outgoing: List[AssociationInfo], incoming: List[AssociationInfo])

  implicit val associationInfoFormat: Format[AssociationInfo]   = Json.format
  implicit val associationsInfoFormat: Format[AssociationsInfo] = Json.format

}
