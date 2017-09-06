package net.corda.cordaftp

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.commonName
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.serialization.SerializationCustomization
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths

val CONFIG_DIR_PREFIX = "/Users/richardgreen/code/cordaftp/" // TODO - better way of providing this

/**
 * We don't really have a complicated verify with this simple cordapp - it just receives files
 */
class FileTransferContract : Contract {
    override val legalContractReference: SecureHash
        get() = SecureHash.zeroHash

    override fun verify(tx: LedgerTransaction) : Unit {
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
data class FileTransferManifestState(val sender: Party, val recipient: Party, val filename: String,
                                     val senderReference: String, val recipientReference: String) : ContractState {
    override val participants: List<AbstractParty> get() = listOf(sender, recipient)
    override val contract: FileTransferContract get() = FileTransferContract()
}

/**
 * The flow that the node sending the file initiates
 */
@InitiatingFlow
@StartableByRPC
class TxFileInitiator(val destinationParty: Party, val theirReference: String, val myReference: String, val filename: String, val attachment: SecureHash, val postSendAction: PostSendAction) : FlowLogic<Unit>() {

    companion object {
        object GENERATING : ProgressTracker.Step("Generating")
        object SENDING : ProgressTracker.Step("Sending")
        object POSTSEND : ProgressTracker.Step("Post send actions")
    }

    override val progressTracker = ProgressTracker(GENERATING, SENDING)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = ProgressTracker.UNSTARTED
        val ptx = TransactionType.General.Builder(notary = serviceHub.networkMapCache.getAnyNotary())
        progressTracker.currentStep = GENERATING
        ptx.addAttachment(attachment)
        val me = this.serviceHub.myInfo.legalIdentity
        val outState = FileTransferManifestState(me, destinationParty, filename, myReference, theirReference)
        ptx.addOutputState(outState)
        val stx = serviceHub.signInitialTransaction(ptx)
        progressTracker.currentStep = SENDING
        sendAndReceive<Any>(destinationParty, stx)
        postSendAction.doAction(filename)
        //progressTracker.currentStep = POSTSEND
    }
}

@InitiatedBy(TxFileInitiator::class)
class RxFileResponder(val otherParty: Party) : FlowLogic<Unit>() {

    override val progressTracker = ProgressTracker()
    companion object {
        object RETRIEVING : ProgressTracker.Step("Retrieving")
        object UNPACKING : ProgressTracker.Step("Unpacking")

        fun determineDestination(cfg: Configuration, name: String, ftmState: FileTransferManifestState) =
                Paths.get(cfg.rxMap.values.filter { ftmState.recipientReference == it.myReference }
                        .first().destinationDirectory, File.separator, name) ?: null
    }

    val configuration: Configuration by lazy {
        val configDir = CONFIG_DIR_PREFIX // TODO: Read from config instead
        val configFileName = "${serviceHub.myInfo.legalIdentity.name.commonName}.json"
        FileConfigurationReader().readConfiguration(configDir + configFileName)
    }

    @Suspendable
    override fun call() {

        logger.info("Startig RxFileResponder")

        progressTracker.currentStep = ProgressTracker.UNSTARTED

        val st = this.receive<SignedTransaction>(otherParty).unwrap {
            it.checkSignaturesAreValid()
            it
        }

        subFlow(ResolveTransactionsFlow(st, otherParty))

        val state = st.tx.outputs.single().data as FileTransferManifestState

        val attach = serviceHub.attachments.openAttachment(st.tx.attachments.first())!!

     //   progressTracker.currentStep = UNPACKING

        run {
            var jis = attach.openAsJAR()

            try {
                while (true) {
                    var nje = jis.nextEntry ?: break
                    if (nje.isDirectory) {
                        continue
                    }
                    val dest = determineDestination(configuration, nje.name, state) ?: continue
                    logger.info("Name is ${nje.name} and path is $dest")
                    val fos = FileOutputStream(dest.toFile())
                    try {
                        jis.copyTo(fos)
                    } finally {
                        fos.close()
                    }
                }
            } finally {
                //  jis.close()
                // TODO: Find out why closing the stream causes an exception
            }

        }
        send(otherParty, Unit)
    }
}

class TemplatePlugin : CordaPluginRegistry() {
    // Whitelisting the required types for serialisation by the Corda node.
    override fun customizeSerialization(custom: SerializationCustomization): Boolean {
        return true
    }
}

