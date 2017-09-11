package net.corda.cordaftp

import net.corda.core.crypto.X509Utilities.getX509Name
import net.corda.core.node.services.ServiceInfo
import net.corda.node.services.transactions.ValidatingNotaryService
import net.corda.nodeapi.User
import net.corda.testing.driver.driver
import java.nio.file.Files
import java.nio.file.Paths

/**
 * This file is exclusively for being able to run your nodes through an IDE (as opposed to running deployNodes)
 * Do not use in a production environment.
 *
 * To debug your CorDapp:
 *
 * 1. Run the "Run Template CorDapp" run configuration.
 * 2. Wait for all the nodes to start.
 * 3. Note the debug ports for each node, which should be output to the console. The "Debug CorDapp" configuration runs
 *    with port 5007, which should be "NodeA". In any case, double-check the console output to be sure.
 * 4. Set your breakpoints in your CorDapp code.
 * 5. Run the "Debug CorDapp" remote debug run configuration.
 */
fun main(args: Array<String>) {
    // No permissions required as we are not invoking flows.
    val user = User("user1", "test", permissions = setOf("StartFlow.net.corda.cordaftp.TxFileInitiator"))
    driver(isDebug = true) {
        startNode(getX509Name("Controller", "London", "root@city.uk.example"), setOf(ServiceInfo(ValidatingNotaryService.type)))

        val nodeNames = listOf(
                getX509Name("NodeA", "Paris", "root@city.fr.example"),
                getX509Name("NodeB", "Rome", "root@city.it.example"),
                getX509Name("NodeC", "New York", "root@city.us.example"))

        for (name in nodeNames) {
            // Copy the app config files in the project root directory to the relevant node base directories
            val nodeDir = Files.createDirectories(baseDirectory(name))
            val appConfigFile = Paths.get("${nodeDir.fileName}.json")
            if (Files.exists(appConfigFile)) {
                Files.copy(appConfigFile, nodeDir.resolve("cordaftp.json"))
            }
            startNode(name, rpcUsers = listOf(user))
        }

        waitForAllNodesToFinish()
    }
}
