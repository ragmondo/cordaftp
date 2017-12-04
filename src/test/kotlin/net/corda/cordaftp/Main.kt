package net.corda.cordaftp

//import net.corda.core.crypto.X509Utilities.getX509Name
//import net.corda.core.node.services.ServiceInfo
//import net.corda.node.services.transactions.ValidatingNotaryService
//import net.corda.nodeapi.User
import net.corda.core.identity.CordaX500Name
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.nodeapi.User
import net.corda.nodeapi.internal.ServiceInfo
import net.corda.testing.driver.driver
import java.nio.file.Files
import java.nio.file.Paths


/**
 * This file is exclusively for being able to run your nodes through an IDE (as opposed to running deployNodes)
 * Do not use in a production environment.*
 */
fun main(args: Array<String>) {
    // No permissions required as we are not invoking flows.
    val user = User("corda", "corda_initial_password", permissions = setOf("StartFlow.net.corda.cordaftp.TxFileInitiator"))
    driver(isDebug = true) {
        startNode(providedName = CordaX500Name( "Controller", "London", "GB"), advertisedServices = setOf(ServiceInfo(SimpleNotaryService.type)))

        val nodeNames = listOf(
                CordaX500Name( "CorpA", "Paris","FR"),
                CordaX500Name( "CorpB", "Rome","IT"),
                CordaX500Name( "CorpC", "New York", "US"))

        for (name in nodeNames) {
            // Copy the app config files in the project root directory to the relevant node base directories
            val nodeDir = Files.createDirectories(baseDirectory(name.toString()))
            val appConfigFile = Paths.get("${nodeDir.fileName}.json")
            if (Files.exists(appConfigFile)) {
                Files.copy(appConfigFile, nodeDir.resolve("cordaftp.json"))
            }
            startNode(providedName = name, rpcUsers = listOf(user))
        }

        waitForAllNodesToFinish()
    }
}
