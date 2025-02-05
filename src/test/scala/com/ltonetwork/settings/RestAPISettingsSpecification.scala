package com.ltonetwork.settings

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RestAPISettingsSpecification extends AnyFlatSpec with Matchers {
  "RestAPISettings" should "read values" in {
    val config   = ConfigFactory.parseString("""
        |lto {
        |  rest-api {
        |    enable: yes
        |    bind-address: "127.0.0.1"
        |    port: 6869
        |    api-key-hash: "BASE58APIKEYHASH"
        |    cors: yes
        |    api-key-different-host: yes
        |  }
        |}
      """.stripMargin)
    val settings = RestAPISettings.fromConfig(config)

    settings.enable should be(true)
    settings.bindAddress should be("127.0.0.1")
    settings.port should be(6869)
    settings.apiKeyHash should be("BASE58APIKEYHASH")
    settings.cors should be(true)
    settings.apiKeyDifferentHost should be(true)
  }

}
