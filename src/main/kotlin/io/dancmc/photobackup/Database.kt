package io.dancmc.photobackup

import org.json.JSONArray
import org.json.JSONObject
import org.neo4j.graphdb.*
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.neo4j.kernel.configuration.BoltConnector
import java.io.File
import java.util.*
import io.dancmc.photobackup.DBResult.OUTCOME.*

class Database {


    companion object {

        var initialised = false
        val bolt = BoltConnector("0")
        var graphDb = {
            File(Main.databaseLocation).mkdirs()
            val g = GraphDatabaseFactory()
                    .newEmbeddedDatabaseBuilder(File(Main.databaseLocation))
                    .setConfig(bolt.type, "BOLT")
                    .setConfig(bolt.enabled, "true")
                    .setConfig(bolt.address, "localhost:7688")
                    .newGraphDatabase()
            registerShutdownHook(g)

            initialised = true
            println("Database initialised")
            g
        }()

        public fun initialiseConstraints() {
            setUniqueConstraint(Label.label("User"), "user_id")
            setUniqueConstraint(Label.label("User"), "username")
            setUniqueConstraint(Label.label("User"), "display_name")
            setUniqueConstraint(Label.label("User"), "email")
            setUniqueConstraint(Label.label("Photo"), "photo_id")
        }




        private fun setUniqueConstraint(label: Label, key: String) {
            val tx = graphDb.beginTx()
            try {
                graphDb.schema()
                        .constraintFor(label)
                        .assertPropertyIsUnique(key)
                        .create()

            } catch (e: Exception) {
                println("Set Constraint : ${e.message}")
            } finally {
                tx.success()
                tx.close()
            }
        }


        public fun registerShutdownHook(graphDb: GraphDatabaseService) {
            // Registers a shutdown hook for the Neo4j instance so that it
            // shuts down nicely when the VM exits (even if you "Ctrl-C" the
            // running application).
            Runtime.getRuntime().addShutdownHook(object : Thread() {
                override fun run() {
                    graphDb.shutdown()
                }
            })

        }

        public fun executeTransaction(tag: String = "", fn: (g: GraphDatabaseService) -> Any?): Any? {
            val tx = graphDb.beginTx()
            try {
                return fn(graphDb)
            } catch (e: Exception) {
                println("$tag : ${e.message}")
                return null
            } finally {
                tx.success()
                tx.close()
            }
        }

        public fun processResult(results: Result, fn: (Map<String, Any>) -> Unit) {

            while (results.hasNext()) {
                fn(results.next())
            }

        }

        public fun addUser(user: User): DBResult {

            val result = executeTransaction("Add User") {
                var userFromDb = graphDb.findNode(Label.label("User"), "username", user.username)
                if(userFromDb!=null){
                    return@executeTransaction "Username already exists"
                }
                userFromDb = graphDb.findNode(Label.label("User"), "email", user.email)
                if(userFromDb!=null){
                    return@executeTransaction "Email already exists"
                }

                val userNode = graphDb.createNode(Label.label("User"))
                val uuid = graphDb.findNode(Label.label("User"), "user_id", user.userID)
                if (uuid != null) {
                    user.userID = UUID.randomUUID().toString()
                }
                addPropertiesToUserNode(user, userNode)
                return@executeTransaction true
            }
            return when (result) {
                null ->  DBResult(FAILED, errorMessage = "DB Failure")
                true ->  DBResult(SUCCESSFUL, payload = user.userID)
                is String ->  DBResult(FAILED, errorMessage = result)
                else ->  DBResult(FAILED, errorMessage = "")
            }
        }

        private fun addPropertiesToUserNode(user: User, node: Node) {
            node.setProperty("user_id", user.userID)
            node.setProperty("username", user.username)
            node.setProperty("password_hash", user.passwordHash)
            node.setProperty("email", user.email)
            node.setProperty("first_name", user.firstName)
            node.setProperty("last_name", user.lastName)
            node.setProperty("email_verified", user.emailVerified)
        }

        public fun getUser(username: String = "", userID: String = ""): Node? {
            return executeTransaction("Get User") {
                return@executeTransaction if (userID.isNotBlank()) {
                    graphDb.findNode(Label.label("User"), "user_id", userID)
                } else {
                    graphDb.findNode(Label.label("User"), "username", username)
                }
            } as Node?
        }

        public fun init() {}

    }


}