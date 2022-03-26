package com.ltonetwork.settings

import com.typesafe.config.ConfigFactory
import com.ltonetwork.state.ByteStr
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class BlockchainSettingsSpecification extends AnyFlatSpec with Matchers {
  "BlockchainSettings" should "read custom values" in {
    val config   = loadConfig(ConfigFactory.parseString("""lto {
        |  directory = "/lto"
        |  data-directory = "/lto/data"
        |  blockchain {
        |    type = CUSTOM
        |    custom {
        |      address-scheme-character = "C"
        |      functionality {
        |        feature-check-blocks-period = 10000
        |        blocks-for-feature-activation = 9000
        |        allow-temporary-negative-until = 1
        |        require-sorted-transactions-after = 3
        |        generation-balance-depth-from-50-to-1000-after-height = 4
        |        minimal-generating-balance-after = 5
        |        allow-transactions-from-future-until = 6
        |        allow-unissued-assets-until = 7
        |        allow-invalid-reissue-in-same-block-until-timestamp = 12
        |        allow-multiple-lease-cancel-transaction-until-timestamp = 14
        |        reset-effective-balances-at-height = 15
        |        block-version-3-after-height = 18
        |        pre-activated-features {
        |          19 = 100
        |          20 = 200
        |        }
        |        double-features-periods-after-height = 21
        |        fee-vote-blocks-period = 22
        |        blocks-for-fee-change = 23
        |        mining-reward = 24
        |        mining-reward-bonus = 25
        |        mining-reward-bonus-period = 26
        |        lease-unbonding-period = 27
        |        burn-addresses = [
        |          "3QavfsNywsiEDVTvXSx1orUfv3uVNK2A8Xb"
        |        ]
        |      }
        |      genesis {
        |        timestamp = 1460678400000
        |        block-timestamp = 1460678400000
        |        signature = "BASE58BLKSGNATURE"
        |        initial-balance = 100000000000000
        |        initial-base-target = 153722867
        |        average-block-delay = 60s
        |        transactions = [
        |          {recipient = "BASE58ADDRESS1", amount = 50000000000001},
        |          {recipient = "BASE58ADDRESS2", amount = 49999999999999}
        |        ]
        |      }
        |    }
        |  }
        |}""".stripMargin))
    val settings = BlockchainSettings.fromConfig(config)

    settings.addressSchemeCharacter should be('C')
    settings.functionalitySettings.featureCheckBlocksPeriod should be(10000)
    settings.functionalitySettings.blocksForFeatureActivation should be(9000)
    settings.functionalitySettings.preActivatedFeatures should be(Map(19 -> 100, 20 -> 200))
    settings.functionalitySettings.doubleFeaturesPeriodsAfterHeight should be(21)
    settings.functionalitySettings.feeVoteBlocksPeriod should be(22)
    settings.functionalitySettings.blocksForFeeChange should be(23)
    settings.functionalitySettings.miningReward should be(24)
    settings.functionalitySettings.miningRewardBonus should be(25)
    settings.functionalitySettings.miningRewardBonusPeriod should be(26)
    settings.functionalitySettings.leaseUnbondingPeriod should be(27)
    settings.functionalitySettings.burnAddresses should be(Set("3QavfsNywsiEDVTvXSx1orUfv3uVNK2A8Xb"))
    settings.genesisSettings.blockTimestamp should be(1460678400000L)
    settings.genesisSettings.timestamp should be(1460678400000L)
    settings.genesisSettings.signature should be(ByteStr.decodeBase58("BASE58BLKSGNATURE").toOption)
    settings.genesisSettings.initialBalance should be(100000000000000L)
    settings.genesisSettings.initialBaseTarget should be(153722867)
    settings.genesisSettings.averageBlockDelay should be(60.seconds)
    settings.genesisSettings.transactions should be(
      Seq(GenesisTransactionSettings("BASE58ADDRESS1", 50000000000001L), GenesisTransactionSettings("BASE58ADDRESS2", 49999999999999L)))
  }

  it should "read testnet settings" in { // regenerated testnet genesis
    val config   = loadConfig(ConfigFactory.parseString("""lto {
        |  directory = "/lto"
        |  data-directory = "/lto/data"
        |  blockchain {
        |    type = TESTNET
        |  }
        |}""".stripMargin))
    val settings = BlockchainSettings.fromConfig(config)

    settings.addressSchemeCharacter should be('T')
    settings.genesisSettings.blockTimestamp should be(1534497076380L)
    settings.genesisSettings.timestamp should be(1534497076380L)
    settings.genesisSettings.signature should be(
      ByteStr.decodeBase58("47pP5r1Kh159XmxcfG2eQVj6dKNhub3mvGgpJovcw7EcZyJswFLYyKGYNV21BGJ8pwkajA75ZLMWFBdv3BzMRMk").toOption)
    settings.genesisSettings.initialBalance should be(100000000000000000L)

    settings.genesisSettings.transactions should be(
      Seq(
        GenesisTransactionSettings("3N6mZMgGqYn9EVAR2Vbf637iej4fFipECq8", 1000000000000000L),
        GenesisTransactionSettings("3N51gbw5W3xvSkcAXtLnXc3SQh2m9e6TBcy", 1000000000000000L),
        GenesisTransactionSettings("3NAxYD4nFbYqHo8gz9Hsfj13s283xNYvGNi", 90000000000000000L),
        GenesisTransactionSettings("3Mv7ajrPLKewkBNqfxwRZoRwW6fziehp7dQ", 1000000000000000L),
        GenesisTransactionSettings("3NARPnCPG4egZbFUQENZ6VDojQqMCpGEG9i", 7000000000000000L),
      ))
  }

  it should "read mainnet settings" in {
    val config   = loadConfig(ConfigFactory.parseString("""lto {
        |  directory = "/lto"
        |  data-directory = "/lto/data"
        |  blockchain {
        |    type = MAINNET
        |  }
        |}""".stripMargin))
    val settings = BlockchainSettings.fromConfig(config)

    settings.genesisSettings.blockTimestamp should be(1547303338475L)
    settings.genesisSettings.timestamp should be(1547303338475L)
    settings.genesisSettings.signature should be(
      ByteStr.decodeBase58("3xfb8SHvXK1eUT73kqq89Ayvt2w5ivy9CM2HHVYZ6H5zjgmbukadkFhis74vXsfak9YjwUCBMQUKsKmiRPmAWaB3").toOption)
    settings.genesisSettings.initialBalance should be(50000000000000000L)
    settings.genesisSettings.transactions should be(
      Seq(
        GenesisTransactionSettings("3JpzrZcSDhzRVeENoqqB98c6hTNg7WJaBKt", 100000000000L),
        GenesisTransactionSettings("3JqBJaDet2MWisRPNLtN5snCxkGHtKaLRHv", 100000000000L),
        GenesisTransactionSettings("3JygettiPvCrb7rSoWDzRHbBWKdMva2d5tu", 49999700000000000L),
        GenesisTransactionSettings("3JyxAP1fpeYXv77FzxihgLsDVMccwLE64rd", 100000000000L),
      ))
  }
}
