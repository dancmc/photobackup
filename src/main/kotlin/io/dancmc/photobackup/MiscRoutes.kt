package io.dancmc.photobackup

import spark.Route
import spark.Spark
import java.io.File
import java.util.*

object MiscRoutes {
    val validSizes = hashSetOf("thumb", "original")

    fun photoQuery(userID: String, photoID: String): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params.put("user_id", userID)
        params.put("photo_id", photoID)
//        return Pair("match (:User{user_id:\$user_id})-[:OWNS]->(:Photo{photo_id:\$photo_id})<-[c:CONTAINS]-(f:FOLDER)\n" +
//                "return f.path as folder_path, c.filename as filename LIMIT 1" , params)
        return Pair("match (p:Photo{photo_id:\$photo_id})\n" +
                "return EXISTS((:User{user_id:\$user_id})-[:OWNS]->(p)) as ownership", params)
    }

    // redirects from danielchan.io/instacopy/photos?size=small&id=qwerty to nginx /instacopy/files/small/qwerty.jpg
    val redirectToStaticPhotos = Route { request, response ->

        val userID = request.attribute("user") as String

        // thumb, original
        val sizeParam = request.queryParamOrDefault("size", "thumb").toString().toLowerCase()
        val photoID = request.queryParamOrDefault("id", "")
        if (photoID.isBlank() || sizeParam !in validSizes) {
            Spark.halt(404, "Invalid parameters")
        }

        Database.executeTransaction {
            val query = photoQuery(userID, photoID)
            val result = it.execute(query.first, query.second)

            var ownership = false
            Database.processResult(result) {
                ownership = it["ownership"] as Boolean
            }

            if (!ownership) {
                Spark.halt(401, "Unauthorized")
            }

        }

        // (for original) get the photo node, retrieve the first folder name and filename, construct path and send to nginx
        // for thumb, just directly construct thumb path
        val userFolder = File(Main.picNginxRoute, userID)
        val sizeFolder = File(userFolder, sizeParam)
        val finalFile = File(sizeFolder, photoID)

        response.header("Content-Type", "image/jpeg")
        println(finalFile.absolutePath)
        response.header("X-Accel-Redirect", finalFile.absolutePath)
    }

}