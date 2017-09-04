package net.corda.cordaftp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.FileInputStream

data class TxConfiguration(val searchDirectory: String,
                           val searchPattern: String,
                           val logDirectory: String,
                           val destinationParty: String,
                           val myReference: String,
                           val theirReference: String) // TODO - change strings to paths etc.

data class RxConfiguration(val myReference: String,
                           val destinationDirectory: String,
                           val logDirectory: String)

data class Configuration(
        var defaults: MutableMap<String, String> = mutableMapOf<String, String>(),
        var txMap: MutableMap<String, TxConfiguration> = mutableMapOf<String, TxConfiguration>(),
        var rxMap: MutableMap<String, RxConfiguration> = mutableMapOf<String, RxConfiguration>()
)

interface ConfigurationReader {
    fun readConfiguration(configSource: String): Configuration
}

class FileConfigurationReader() : ConfigurationReader {
    override fun readConfiguration(configSource: String) =
            jacksonObjectMapper().readValue<Configuration>(FileInputStream(configSource))
}

class FakeConfigurationReader() : ConfigurationReader {
    override fun readConfiguration(configSource: String): Configuration {

        val dc1 = TxConfiguration("/Users/richardgreen/example_send/blerg", ".*\\.txt", "/Users/richardgreen/example_send/log", "NodeA", "my_reference", "other_nodes_reference_1")
        val dc2 = TxConfiguration("/Users/richardgreen/example_send/blah", ".*\\.txt", "/Users/richardgreen/example_send/log", "NodeA", "my_reference", "other_nodes_reference_2")

        val inc1 = RxConfiguration("incoming_ref1", "/Users/richardgreen/incoming_1", "/Users/richardgreen/log")
        val inc2 = RxConfiguration("incoming_ref2", "/Users/richardgreen/incoming_2", "/Users/richardgreen/log")

        return Configuration(
                mutableMapOf(Pair("environment", "dev")),
                mutableMapOf(
                        Pair("my_config_name1", dc1),
                        Pair("my_name", dc2)),
                mutableMapOf(
                        Pair("incoming_1", inc1),
                        Pair("incoming_2", inc2)
                )
        )
    }
}
