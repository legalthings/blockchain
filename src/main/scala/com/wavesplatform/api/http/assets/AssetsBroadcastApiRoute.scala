package com.wavesplatform.api.http.assets

import akka.http.scaladsl.server.Route
import com.wavesplatform.network._
import com.wavesplatform.settings.RestAPISettings
import com.wavesplatform.state.diffs.TransactionDiffer.TransactionValidationError
import com.wavesplatform.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import io.swagger.annotations._
import javax.ws.rs.Path
import com.wavesplatform.api.http._
import com.wavesplatform.http.BroadcastRoute
import com.wavesplatform.transaction.{Transaction, ValidationError}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Left, Right}

@Path("/assets/broadcast")
@Api(value = "assets")
case class AssetsBroadcastApiRoute(settings: RestAPISettings, utx: UtxPool, allChannels: ChannelGroup) extends ApiRoute with BroadcastRoute {

  override val route: Route = pathPrefix("assets" / "broadcast") {
    batchTransfer
  }

  @Path("/batch-transfer")
  @ApiOperation(value = "Batch transfer operation",
                notes = "Transfer assets to new addresses",
                httpMethod = "POST",
                produces = "application/json",
                consumes = "application/json")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "body",
        value = "Array json with data",
        required = true,
        paramType = "body",
        dataType = "com.wavesplatform.api.http.assets.SignedTransferV2Request",
        allowMultiple = true,
        defaultValue =
          "[{\n  \"assetId\": \"E9yZC4cVhCDfbjFJCc9CqkAtkoFy5KaCe64iaxHM2adG\",\n  \"senderPublicKey\": \"CRxqEuxhdZBEHX42MU4FfyJxuHmbDBTaHMhM3Uki7pLw\",\n  \"recipient\": \"3Mx2afTZ2KbRrLNbytyzTtXukZvqEB8SkW7\",\n  \"fee\": 100000,\n  \"amount\": 5500000000,\n  \"attachment\": \"BJa6cfyGUmzBFTj3vvvaew\",\n  \"timestamp\": 1479222433704, \n  \"signature\": \"2TyN8pNS7mS9gfCbX2ktpkWVYckoAmRmDZzKH3K35DKs6sUoXHArzukV5hvveK9t79uzT3cA8CYZ9z3Utj6CnCEo\"\n, {\n  \"assetId\": \"E9yZC4cVhCDfbjFJCc9CqkAtkoFy5KaCe64iaxHM2adG\",\n  \"senderPublicKey\": \"CRxqEuxhdZBEHX42MU4FfyJxuHmbDBTaHMhM3Uki7pLw\",\n  \"recipient\": \"3Mx2afTZ2KbRrLNbytyzTtXukZvqEB8SkW7\",\n  \"fee\": 100000,\n  \"amount\": 5500000000,\n  \"attachment\": \"BJa6cfyGUmzBFTj3vvvaew\",\n  \"timestamp\": 1479222433704, \n  \"signature\": \"2TyN8pNS7mS9gfCbX2ktpkWVYckoAmRmDZzKH3K35DKs6sUoXHArzukV5hvveK9t79uzT3cA8CYZ9z3Utj6CnCEo\"\n}]"
      )
    ))
  def batchTransfer: Route = (path("batch-transfer") & post) {
    json[List[SignedTransferRequests]] { reqs =>
      val r = Future
        .traverse(reqs) { req =>
          Future {
            req.eliminate(
              _.toTx,
              _.eliminate(
                _.toTx,
                _ => Left(ValidationError.UnsupportedTransactionType)
              )
            )
          }
        }
        .map { xs: List[Either[ValidationError, Transaction]] =>
          utx.batched { ops =>
            xs.map {
              case Left(e)   => Left(e)
              case Right(tx) => ops.putIfNew(tx).map { case (isNew, _) => (tx, isNew) }
            }
          }
        }
        .map { xs =>
          xs.map {
            case Left(TransactionValidationError(_: ValidationError.AlreadyInTheState, tx)) => Right(tx -> false)
            case Left(e)                                                                    => Left(ApiError.fromValidationError(e))
            case Right(x)                                                                   => Right(x)
          }
        }

      r.foreach { xs =>
        val newTxs = xs.collect { case Right((tx, true)) => tx }
        allChannels.broadcastTx(newTxs)
      }

      r.map { xs =>
        xs.map {
          case Left(e)        => e.json
          case Right((tx, _)) => tx.json()
        }
      }
    }
  }

  @Path("/transfer")
  @ApiOperation(
    value = "Broadcast signed Asset transfer",
    notes = "Publish signed Asset transfer transaction to the Blockchain",
    httpMethod = "POST",
    consumes = "application/json",
    produces = "application/json"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "body",
        value = "Json with signed Transfer transaction",
        required = true,
        paramType = "body",
        dataType = "com.wavesplatform.api.http.assets.SignedTransferV2Request"
      )))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json with signed Asset transfer transaction"),
      new ApiResponse(code = 400, message = "Json with error description", response = classOf[ApiErrorResponse])
    ))
  def transfer: Route = (path("transfer") & post) {
    json[SignedTransferRequests] { transferReq =>
      doBroadcast(
        transferReq.eliminate(
          _.toTx,
          _.eliminate(
            _.toTx,
            _ => Left(ValidationError.UnsupportedTransactionType)
          )
        )
      )
    }
  }

  @Path("/exchange")
  @ApiOperation(
    value = "Broadcast signed Exchange transaction",
    notes = "Publish signed Exchange transaction to the Blockchain",
    httpMethod = "POST",
    consumes = "application/json",
    produces = "application/json"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "body",
        value = "Json with signed Transfer transaction",
        required = true,
        paramType = "body",
        dataType = "com.wavesplatform.api.http.assets.SignedExchangeRequest"
      )))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json with signed Exchange transfer transaction"),
      new ApiResponse(code = 400, message = "Json with error description", response = classOf[ApiErrorResponse])
    ))
  def exchange: Route = (path("exchange") & post) {
    json[SignedExchangeRequest] { req =>
      doBroadcast(req.toTx)
    }
  }
}
