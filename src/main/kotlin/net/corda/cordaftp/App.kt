package net.corda.cordaftp


import co.paralleluniverse.fibers.Suspendable
import net.corda.analytics.GoogleCordaAnalytics
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

val analytics = GoogleCordaAnalytics("UA-106986514-1")

/**
 * We don't really have a complicated verify with this simple cordapp - it just receives files
 */
@LegalProseReference(uri = "/some/uri/docs.txt")
class FileTransferContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        requireThat {
            "No input states (number of inputs is ${tx.inputStates.size})" using (tx.inputStates.isEmpty())
            "One output state (number of outputs is ${tx.outputStates.size})" using (tx.outputStates.size == 1)
            "Output state is FileTransferManifestState" using (tx.outputStates.single() is FileTransferManifestState)
            "Only two attachments (one data-file, one contract) (actual size is ${tx.attachments.size})" using (tx.attachments.size == 2)
        }
    }

    // This can go anywhere, but I've put it here for now.
    companion object {
        val FTCONTRACT = "net.corda.cordaftp.FileTransferContract"
    }
}


/**
 * A basic manifest
 */
data class FileTransferManifestState(val sender: Party,
                                     val recipient: Party,
                                     val filename: String,
                                     val senderReference: String,
                                     val recipientReference: String) : ContractState {
    override val participants: List<AbstractParty> get() = listOf(sender, recipient)

    class FileTransferCommand : TypeOnlyCommandData()
}

/**
 * The flow that the node sending the file initiates
 */
@InitiatingFlow
@StartableByRPC
class TxFileInitiator(private val destinationParty: Party,
                      private val theirReference: String,
                      private val myReference: String,
                      private val file: String,
                      private val attachment: SecureHash,
                      private val postSendAction: PostSendAction?) : FlowLogic<Unit>() {


    companion object {
        object GENERATING_TX : ProgressTracker.Step("Generating transaction")
        object SENDING_TX : ProgressTracker.Step("Sending")
        object POSTSEND_ACTIONS : ProgressTracker.Step("Post send actions")
    }

    override val progressTracker = ProgressTracker(GENERATING_TX, SENDING_TX, POSTSEND_ACTIONS)

    @Suspendable
    override fun call() {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        progressTracker.changes.subscribe {
            analytics.flowProgress(it.toString())
        }

        progressTracker.currentStep = ProgressTracker.UNSTARTED
        val ptx = TransactionBuilder(notary = notary)

        progressTracker.currentStep = GENERATING_TX
        val outState = FileTransferManifestState(ourIdentity, destinationParty, file, myReference, theirReference)
        val cmd = FileTransferManifestState.FileTransferCommand()
        ptx.addAttachment(attachment)
                .addOutputState(outState, FileTransferContract.FTCONTRACT)
                .addCommand(cmd, ourIdentity.owningKey)
        val stx = serviceHub.signInitialTransaction(ptx)

        progressTracker.currentStep = SENDING_TX
        val flowSession = initiateFlow(destinationParty)
        subFlow(SendTransactionFlow(flowSession, stx))

        progressTracker.currentStep = POSTSEND_ACTIONS
        postSendAction?.doAction(file)
    }
}

/**
 * This flow is started on the receiving node's side when it sees the TxFileInitiator flow send it something
 */
@InitiatedBy(TxFileInitiator::class)
class RxFileResponder(private val otherSideSession: FlowSession) : FlowLogic<Unit>() {
    companion object {
        object RECEIVING_TX : ProgressTracker.Step("Retrieving transaction.")
        object UNPACKING : ProgressTracker.Step("Unpacking")
    }

    override val progressTracker = ProgressTracker(RECEIVING_TX, UNPACKING)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = RECEIVING_TX
        val stx = subFlow(ReceiveTransactionFlow(otherSideSession, true))
        val state = stx.tx.outputsOfType<FileTransferManifestState>().single()
        val attachment = serviceHub.attachments.openAttachment(stx.tx.attachments[0])!!

        progressTracker.changes.subscribe {
            analytics.flowProgress(it.toString())
        }

        progressTracker.currentStep = UNPACKING

        // This part ensures that the attachment received is a jar (the Corda spec. requires that this is the case) and
        // then extracts the file to the correct destination directory.

        val configHolder = serviceHub.cordaService(ConfigHolder::class.java)
        attachment.openAsJAR().use { jar ->
            while (true) {
                val nje = jar.nextEntry ?: break
                if (nje.isDirectory) {
                    continue
                }
                val destFile = configHolder.getDestDir(state.recipientReference).resolve(nje.name)
                logger.info("Name is ${nje.name} and path is $destFile")
                Files.newOutputStream(destFile).use {
                    jar.copyTo(it)
                }
            }
        }
    }
}

/**
 * The platform currently doesn't provide CorDapps a way to access their own config, so we use the CordaService concept
 * to read in our own config file once and store it for use by our flows.
 */
@CordaService
class ConfigHolder(@Suppress("UNUSED_PARAMETER") service: ServiceHub) : SingletonSerializeAsToken() {
    private val destDirs: Map<String, Path>
    init {
        // Look for a file called cordaftp.json in the current working directory (which is usually the node's base dir)
        val configFile = Paths.get("cordaftp.json")
        destDirs = if (Files.exists(configFile)) {
            FileConfigurationReader()
                    .readConfiguration(Files.newInputStream(configFile))
                    .rxMap
                    .values
                    .associateBy({ it.myReference }, { Files.createDirectories(Paths.get(it.destinationDirectory)) })
        } else {
            emptyMap()
        }
    }

    fun getDestDir(reference: String): Path {
        return destDirs[reference] ?: throw IllegalArgumentException("Unknown reference: $reference")
    }
}


@CordaService
class NullClass(val service: ServiceHub) : SingletonSerializeAsToken() {
    init {
        val me = service.myInfo.legalIdentities.first()
        analytics.overrides["cid"] = me.toString()
        analytics.overrides["geoid"] = me.name.country.toString()
        analytics.nodeStart()
    }
}
