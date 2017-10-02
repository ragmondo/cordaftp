package net.corda.cordaftp


import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.internal.ResolveTransactionsFlow
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SerializationCustomization
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * We don't really have a complicated verify with this simple cordapp - it just receives files
 */
@LegalProseReference(uri="/some/uri/docs.txt")
class FileTransferContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        requireThat {
            "No input states" using (tx.inputStates.isEmpty())
            "One output state" using (tx.outputStates.size == 1)
            "Output state is FileTransferManifestState" using (tx.outputStates.single() is FileTransferManifestState)
            "Only one attachment" using (tx.attachments.size == 1)
        }
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
        object GENERATING : ProgressTracker.Step("Generating")
        object SENDING : ProgressTracker.Step("Sending")
        object POSTSEND : ProgressTracker.Step("Post send actions")
    }

    override val progressTracker = ProgressTracker(GENERATING, SENDING)

    @Suspendable
    override fun call() {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        progressTracker.currentStep = ProgressTracker.UNSTARTED
        val ptx = TransactionBuilder(notary = notary)
        //val ptx = TransactionType.General.Builder(notary = serviceHub.networkMapCache.getAnyNotary())
        progressTracker.currentStep = GENERATING
        ptx.addAttachment(attachment)
        val me = this.serviceHub.myInfo.legalIdentities.first()
        val outState = FileTransferManifestState(me, destinationParty, file, myReference, theirReference)
        ptx.addOutputState(TransactionState(outState, "FileTransferContract", notary))
        val stx = serviceHub.signInitialTransaction(ptx)
        progressTracker.currentStep = SENDING
        val flowSession = initiateFlow(destinationParty)
        flowSession.send(stx)
        postSendAction?.doAction(file)
        //progressTracker.currentStep = POSTSEND

    }
}

// The platform currently doesn't provide CorDapps a way to access their own config, so we use the CordaService concept
// to read in our own config file once and store it for use by our flows.
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

@InitiatedBy(TxFileInitiator::class)
class RxFileResponder(private val otherSideSession: FlowSession) : FlowLogic<Unit>() {
    companion object {
        object RETRIEVING : ProgressTracker.Step("Retrieving")
        object UNPACKING : ProgressTracker.Step("Unpacking")
    }

    override val progressTracker = ProgressTracker(UNPACKING)

    @Suspendable
    override fun call() {
        val st = otherSideSession.receive<SignedTransaction>().unwrap {
            it.checkSignaturesAreValid()
            it
        }

        subFlow(ResolveTransactionsFlow(st, otherSideSession))

        val state = st.tx.outputs.single().data as FileTransferManifestState

        val attachment = serviceHub.attachments.openAttachment(st.tx.attachments[0])!!

        progressTracker.currentStep = UNPACKING

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

class TemplatePlugin : CordaPluginRegistry() {
    // Whitelisting the required types for serialisation by the Corda node.
    override fun customizeSerialization(custom: SerializationCustomization): Boolean {
        return true
    }
}

