package io.dancmc.photobackup

import org.json.JSONObject
import spark.Route
import java.util.*

object UserRoutes {
    val register = Route { request, response ->
        val requestJson = JSONObject(request.body())
        val username = requestJson.optString("username", "").toLowerCase()
        val password = requestJson.optString("password", "")
        val firstName = requestJson.optString("first_name", "")
        val lastName = requestJson.optString("last_name", "")
        val email = requestJson.optString("email", "")
        if (username.isBlank() || password.isBlank() || email.isBlank()) {
            return@Route JSONObject().fail(-1, "Missing field")
        }

        val user = User(userID = UUID.nameUUIDFromBytes(username.toByteArray()).toString(), username = username, passwordHash = Utils.Password.hashPassword(password),
                email = email, emailVerified = false, firstName = firstName, lastName = lastName)

        val result = Database.addUser(user)
        when (result.result){
            DBResult.OUTCOME.SUCCESSFUL->{
                val jwt = Utils.Token.createAppToken(result.payload as String)
                JSONObject().success()
                        .put("jwt", jwt)
                        .put("user_id", result.payload)
                        .put("username", username)
            }
            DBResult.OUTCOME.FAILED->JSONObject().fail(message = result.errorMessage)
        }
    }

    val login = Route { request, response ->
        val requestJson = JSONObject(request.body())
        val username = requestJson.optString("username", "").toLowerCase()
        val password = requestJson.optString("password", "")

        val user = Database.getUser(username = username)
        if (user == null) {
            JSONObject().fail(message = "User not found")
        } else {
            Database.executeTransaction {
                if (Utils.Password.verifyPassword(user.getProperty("password_hash") as String, password)) {
                    JSONObject().success()
                            .put("jwt", Utils.Token.createAppToken(user.getProperty("user_id") as String))
                            .put("username", username)
                            .put("user_id", user.getProperty("user_id") as String)
                } else {
                    JSONObject().fail(message = "Wrong password")
                }
            }
        }
    }




    val validate = Route { request, response ->
        val userID = request.attribute("user") as String
        Database.executeTransaction {
            val user = it.findNode({"User"}, "user_id", userID)
            if(user==null){
                return@executeTransaction JSONObject().fail(code = Errors.JWT_NOT_VALID, message = "User JWT not valid")
            }else {
                val jwt = Utils.Token.createAppToken(userID)
                val username = user.getProperty("username") as String
                return@executeTransaction  JSONObject().success()
                        .put("jwt", jwt)
                        .put("username", username)
                        .put("user_id", userID)
            }
        } as JSONObject? ?: JSONObject().fail(message = "DB Fail")
    }


}