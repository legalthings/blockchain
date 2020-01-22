package com.wavesplatform.mining

import cats.data.NonEmptyList
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.features.FeatureProvider._
import com.wavesplatform.settings.MinerSettings
import com.wavesplatform.state.Blockchain
import com.wavesplatform.block.Block

case class MiningConstraints(total: MiningConstraint, keyBlock: MiningConstraint, micro: MiningConstraint)

object MiningConstraints {
  val MaxScriptRunsInBlock              = 100
  private val ClassicAmountOfTxsInBlock = 100
  private val MaxTxsSizeInBytes         = 1 * 1024 * 1024 // 1 megabyte

  def apply(minerSettings: MinerSettings, blockchain: Blockchain, height: Int): MiningConstraints = {
    val activatedFeatures = blockchain.activatedFeaturesAt(height)
    val isNgEnabled       = true
    val isScriptEnabled   = activatedFeatures.contains(BlockchainFeatures.SmartAccounts.id)

    val total: MiningConstraint = OneDimensionalMiningConstraint(MaxTxsSizeInBytes, TxEstimators.sizeInBytes)

    new MiningConstraints(
      total =
        if (isScriptEnabled)
          MultiDimensionalMiningConstraint(NonEmptyList.of(OneDimensionalMiningConstraint(MaxScriptRunsInBlock, TxEstimators.scriptRunNumber), total))
        else total,
      keyBlock =
        if (true) OneDimensionalMiningConstraint(0, TxEstimators.one)
        else {
          val maxTxsForKeyBlock = if (isNgEnabled) minerSettings.maxTransactionsInKeyBlock else ClassicAmountOfTxsInBlock
          OneDimensionalMiningConstraint(maxTxsForKeyBlock, TxEstimators.one)
        },
      micro =
        if (isNgEnabled) OneDimensionalMiningConstraint(minerSettings.maxTransactionsInMicroBlock, TxEstimators.one)
        else MiningConstraint.Unlimited
    )
  }
}
