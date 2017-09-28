package net.corda.cordaftp

import net.corda.analytics.GoogleCordaAnalytics
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort
import java.io.File
import java.io.FileInputStream
import java.nio.file.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


val ARBITRARY_MAX_FILE_SIZE = 5_000_000

fun main(args: Array<String>) {
    val proxy = loginToCordaNode(args)
    val configName = "${proxy.nodeInfo().legalIdentities.first().name.organisation}.json"
    val config = FileConfigurationReader().readConfiguration(FileInputStream(configName))
    transferFilesForever(config, proxy)
}

fun loginToCordaNode(args: Array<String>): CordaRPCOps {
    val nodeAddress = NetworkHostAndPort.parse(args[0])
    val client = CordaRPCClient(nodeAddress)
    return client.start("user1", "test").proxy
}
/*
 *
 * As titled, this function loops forever (until interrupted), scanning the directories given by the txMap section
 * in the config file and when there is a match, it runs the startFlow() function with details of that match
 * We use the Java WatchService to take care of alerting us when a relevant file appears.
 */
fun transferFilesForever(config: Configuration, proxy: CordaRPCOps) {
    val keysConfigMap = mutableMapOf<WatchKey, Pair<String, TxConfiguration>>()
    val watcher = FileSystems.getDefault().newWatchService()
    for((key, value) in config.txMap) {
        println("Configuration: $key")
        println(value.toString().replace(",","\n\t\t"))
        println()
        val searchDir = Paths.get(value.searchDirectory)
        Files.createDirectories(searchDir)
        val watchkey = searchDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
        keysConfigMap[watchkey] = Pair(key, value)
    }

    val analytics = GoogleCordaAnalytics("UA-106986514-1")


    while (true) {
        println("In main loop and watching...")
        val key = watcher.take()
        val (configName, config) = keysConfigMap[key]!!
        val pattern =  config.searchPattern.toRegex()

        println("Potentially found something on Configuration: $configName - ${config.searchDirectory} for ${config.searchPattern} ")

        val events = key.pollEvents()
        for (e in events) {
            val filename = e.context().toString()
            if (pattern.containsMatchIn(filename)) {
                println("Filename $filename matches pattern $pattern")

                val file = Paths.get(config.searchDirectory, filename).toAbsolutePath()
                if (Files.size(file) > ARBITRARY_MAX_FILE_SIZE) {
                    println("Filesize ${Files.size(file)} exceeds $ARBITRARY_MAX_FILE_SIZE. Ignoring")
                }
                else {
                    startFlow(proxy, config, file)
                }
            }
            else {
                println("No match - no further action")
            }
        }
        println("-------")
        key.reset()
    }
}

fun startFlow(proxy: CordaRPCOps, config: TxConfiguration, file: Path) {
    println("Start transfer Flow with :")
    println(" destination: ${config.destinationParty}")
    println(" their reference: ${config.theirReference}")
    println(" my reference: ${config.myReference}")
    println(" filename: $file")
    println(" log directory: ${config.logDirectory}")

    // TODO: Make this use pipedinput / output
    val fo = File.createTempFile("/tmp",".corda.zip")
    println(" --> tmp file is $fo")

    ZipOutputStream(fo.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry(file.fileName.toString()))
        Files.newInputStream(file).use {
            it.copyTo(zos)
        }
    }

    val attachmentHash = proxy.uploadAttachment(fo.inputStream())
    val otherParty = proxy.partiesFromName(config.destinationParty, false).first()

    println("--> Destination party confirmed as $otherParty")

    try {
        val flowHandle = proxy.startTrackedFlowDynamic(
                TxFileInitiator::class.java,
                otherParty,
                config.theirReference,
                config.myReference,
                file.toString(),
                attachmentHash,
                config.postSendAction)

        flowHandle.progress.subscribe { evt ->
            System.out.printf(">> %s\n", evt)
        }

        // The line below blocks and waits for the flow to return.
        flowHandle.returnValue.get()
    } catch (ex: Throwable) {
        ex.printStackTrace()
        /* It's not a real system - bail out on error */
        TODO("error handling for $ex")
    }
}

