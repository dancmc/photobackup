package io.dancmc.photobackup

import org.json.JSONArray
import org.json.JSONObject
import org.neo4j.graphdb.Node
import spark.Route
import java.util.*

object PhotoRoutes {

    private fun completeQuery(userID: String): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params["user_id"] = userID
        return Pair("match (:User{user_id:\$user_id})-[:OWNS]->(p:Photo), (p)<-[c:CONTAINS]-(f:Folder), (p)<-[d:DESCRIBES]-(t:Tag)\n" +
                "return p as photo, collect({filename:c.filename,folderpath:f.folderpath}) as folders, collect(d.timestamp) as tag_timestamps", params)
    }

    val complete = Route { request, response ->
        val userID = request.attribute("user") as String

        Database.executeTransaction {

            val query = completeQuery(userID)
            val results = it.execute(query.first, query.second)
            val json = JSONObject().success()
            val photoArray = JSONArray()
            json.put("photos", photoArray)


            Database.processResult(results) {
                val photoObject = JSONObject()
                photoArray.put(photoObject)

                val photoNode = it["photo"] as Node
                photoObject.put("md5", photoNode.getProperty("md5") as String)
                photoObject.put("bytes", photoNode.getProperty("bytes") as Long)
                photoObject.put("notes_updated", photoNode.getProperty("notes_updated") as Long)


                val folders = it["folders"] as ArrayList<HashMap<String?, String?>>
                val foldersArray = JSONArray().apply {
                    folders.forEach { folder ->
                        this.put(JSONObject()
                                .put("filename", folder["filename"])
                                .put("folderpath", folder["folderpath"]))
                    }
                }
                photoObject.put("folders", foldersArray)

                val tagTimes = it["tag_timestamps"] as ArrayList<Long>
                val latestTagTimestamp = tagTimes.max() ?: 0L
                photoObject.put("tags_updated", latestTagTimestamp)

            }

            return@executeTransaction json
        } as JSONObject? ?: JSONObject().fail(message = "DB Fail")

    }



}