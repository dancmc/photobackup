package io.dancmc.photobackup

import apoc.cypher.Cypher
import apoc.help.Help
import apoc.text.Strings
import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.json.JSONObject
import org.neo4j.internal.kernel.api.exceptions.KernelException
import org.neo4j.kernel.impl.proc.Procedures
import org.neo4j.kernel.internal.GraphDatabaseAPI
import spark.Spark

class Main {

    companion object {
        val picRoute = "/instacopy/files"
        val picFolder = "/users/daniel/downloads/photobackup/photos"
        val domain = "http://10.0.0.1:8080/photobackup/v1"
        val databaseLocation = "/users/daniel/downloads/photobackup/photobackup_neo4j"
//        val picFolder = "/mnt/www/instacopy/photos"
//        val domain = "https://dancmc.io/instacopy/v1"
//        val databaseLocation  = "/mnt/www/instacopy/social"


        @JvmStatic
        fun main(args: Array<String>) {


            Spark.port(6800)

            // Do authorisation check
            Spark.before("/*") { request, response ->
                val userId: String
                val tokenDecode = request.decodeToken()


                when {
                    tokenDecode is JSONObject -> {
                        val path = request.pathInfo()
                        if (!path.contains("/user/login") && !path.contains("/user/register") && !path.contains("/admin")) {
                            Spark.halt(401, tokenDecode.toString())
                        }
                    }
                    else -> {
                        userId = (tokenDecode as DecodedJWT).audience[0].toString()
                        request.attribute("user", userId)
                    }
                }
                response.type("application/json")
            }

            Spark.path("/photobackup/v1") {
                Spark.path("/user") {
                    Spark.post("/register", UserRoutes.register)
                    Spark.post("/login", UserRoutes.login)
                    Spark.get("/validate", UserRoutes.validate)
                }

                Spark.path("/photo") {
                    Spark.get("/complete", PhotoRoutes.complete)
                }

                Spark.path("/static") {
                    Spark.get("/photos", MiscRoutes.redirectToStaticPhotos)

                }

                Spark.path("/admin") {
                    Spark.get("/changePassword", AdminRoutes.changePassword)
                    Spark.get("/kill", AdminRoutes.kill)
                }
            }



            Database.init()

            runBlocking {
                while (!Database.initialised) {
                    delay(1000)
                }

                val proceduresToRegister = listOf(Help::class.java, Cypher::class.java)
                val functionsToRegister = listOf(Strings::class.java)

                val procedures = (Database.graphDb as GraphDatabaseAPI).dependencyResolver.resolveDependency(Procedures::class.java)
                proceduresToRegister.forEach { proc ->
                    try {
                        procedures.registerProcedure(proc)
                    } catch (e: KernelException) {
                        throw RuntimeException("Error registering $proc", e)
                    }
                }
                functionsToRegister.forEach { fn ->
                    try {
                        procedures.registerFunction(fn)
                    } catch (e: KernelException) {
                        throw RuntimeException("Error registering $fn", e)
                    }
                }

                Database.initialiseConstraints()


                return@runBlocking
            }
        }

    }

}