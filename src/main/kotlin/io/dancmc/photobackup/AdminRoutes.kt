package io.dancmc.photobackup
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.json.JSONObject
import spark.Route

object AdminRoutes {



    val changePassword = Route { request, response ->
        val displayName = request.queryParamOrDefault("display_name", "").toLowerCase()
        val password = request.queryParamOrDefault("password", "")

        Database.executeTransaction {
            val userNode = it.findNode({ "User" }, "display_name", displayName)
            userNode.setProperty("password_hash", Utils.Password.hashPassword(password))
        }

    }

    val kill = Route { request, response ->
        launch {
            delay(2000)
            System.exit(0)
        }
        return@Route "Shutting down"
    }

}