package com.wavesplatform.transaction.smart.script

import cats.implicits._
import com.wavesplatform.lang.v1.evaluator.EvaluatorV1
import com.wavesplatform.lang.v1.evaluator.ctx.EvaluationContext
import com.wavesplatform.lang.{ExecutionError, ExprEvaluator}
import com.wavesplatform.state._
import monix.eval.Coeval
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.transaction.smart.BlockchainContext

object ScriptRunner {

  def apply[A, T <: Transaction](height: Int, tx: T, blockchain: Blockchain, script: Script): (ExprEvaluator.Log, Either[ExecutionError, A]) =
    script match {
      case Script.Expr(expr) =>
        val ctx = BlockchainContext.build(
          AddressScheme.current.chainId,
          Coeval.evalOnce(tx),
          Coeval.evalOnce(height),
          blockchain
        )
        EvaluatorV1.applywithLogging[A](ctx, expr)

      case _ => (List.empty, "Unsupported script version".asLeft[A])
    }

}
