package net.corda.cordaftp

import net.corda.client.rpc.CordaRPCClient
import net.corda.core.crypto.commonName
import net.corda.core.internal.readAll
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.parseNetworkHostAndPort
import java.io.File
import java.nio.file.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


fun main(args: Array<String>) {
    var proxy = loginToCordaNode(args)
    val configName = "${proxy.nodeIdentity().legalIdentity.name.commonName}.json"
    val config = FileConfigurationReader().readConfiguration(configName)
    transferFilesForever(config, proxy)
}

fun loginToCordaNode(args: Array<String>): CordaRPCOps {
    val nodeAddress = args[0].parseNetworkHostAndPort()
    val client = CordaRPCClient(nodeAddress)
    val proxy = client.start("user1", "test").proxy
    return proxy
}

fun transferFilesForever(config: Configuration, proxy: CordaRPCOps) {

    var keysConfigMap = mutableMapOf<WatchKey, Pair<String, TxConfiguration>>()
    val watcher = FileSystems.getDefault().newWatchService()
    for(dc in config.txMap) {
        println("Configuration: ${dc.key}")
        println(dc.value.toString().replace(",","\n\t\t"))
        println()
        var p = Paths.get(dc.value.searchDirectory)!!
        val watchkey = p.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
        keysConfigMap.put(watchkey, Pair(dc.key, dc.value))
    }

    while(true) {
        println("In main loop and watching...")
        val key = watcher.take()
        var configPair = keysConfigMap.get(key)!!
        val configName = configPair.first
        val config = configPair.second
        val pattern =  config.searchPattern.toRegex()

        println("Potentially found something on Configuration: ${configName} - ${config.searchDirectory} for ${config.searchPattern} ")

        val events = key.pollEvents()
        for (e in events) {
            val filename = e.context().toString()
            if( pattern.containsMatchIn(filename) ) {
                println("Filename $filename matches pattern $pattern")
                val fullFileName = Paths.get(config.searchDirectory, File.separator, filename)
                startFlow(proxy, config.destinationParty, config.theirReference, config.myReference, fullFileName, config.logDirectory, config.postSendAction)
            }
            else {
                println("No match - no further action")
            }
        }
        println("-------")
        key.reset()
    }
}

fun startFlow(proxy: CordaRPCOps, destinationParty: String, theirReference: String, myReference: String, fullFileName: Path, logDirectory: String, postSendAction: PostSendAction) {
    println("Start transfer Flow with :")
    println(" destination: ${destinationParty}")
    println(" their reference: ${theirReference}")
    println(" my reference: ${myReference}")
    println(" filename: $fullFileName")
    println(" log directory: ${logDirectory}")

    // TODO: Make this use pipedinput / output
    var fo = File.createTempFile("/tmp",".corda.zip")
    println(" --> tmp file is $fo")

    var jos = ZipOutputStream(fo.outputStream())
    val je = ZipEntry(fullFileName.fileName.toString())
    jos.putNextEntry(je)
    jos.write(fullFileName.readAll())
    jos.close()

    val attachmentHash = proxy.uploadAttachment(fo.inputStream())
    val otherParty = proxy.partiesFromName(destinationParty, false).first()

    println("--> Destination party confirmed as $otherParty")

    try {
        val flowHandle = proxy
                .startTrackedFlowDynamic(TxFileInitiator::class.java, otherParty, theirReference, myReference, fullFileName.toString(), attachmentHash, postSendAction)

        flowHandle.progress.subscribe({
            evt -> System.out.printf(">> %s\n", evt)
        })

        // The line below blocks and waits for the flow to return.
        val result = flowHandle
                .returnValue
                .get()
    } catch (ex: Throwable) {
        println(ex)
        /* It's not a real system - bail out on error */
        TODO("error handling for $ex")
    }
}

