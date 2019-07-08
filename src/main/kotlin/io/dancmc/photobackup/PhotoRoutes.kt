package io.dancmc.photobackup

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.apache.commons.fileupload.FileItemStream
import org.apache.commons.fileupload.servlet.ServletFileUpload
import org.apache.commons.fileupload.util.Streams
import org.json.JSONArray
import org.json.JSONObject
import org.neo4j.graphdb.Direction
import org.neo4j.graphdb.Label
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.RelationshipType
import spark.Route
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.servlet.MultipartConfigElement
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

data class UploadResult(val success: Boolean, val originalFile: File, val thumbFile: File)

object PhotoRoutes {

    private fun completeQuery(userID: String): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params["user_id"] = userID
        return Pair("match (:User{user_id:\$user_id})-[:OWNS]->(p:Photo), (p)<-[c:CONTAINS]-(f:Folder) optional match (p)<-[:DESCRIBES]-(t:Tag)\n" +
                "return p as photo, collect(distinct {filename:c.filename,folderpath:f.folderpath}) as folders, collect(distinct(t.name)) as tags", params)
    }

    val complete = Route { request, response ->
        val userID = request.attribute("user") as String
        val getDeleted = request.queryParamOrDefault("deleted", "false")!!.toBoolean()

        val doTransaction = {
            Database.executeTransaction { db ->

                var time = System.currentTimeMillis()

                val query = completeQuery(userID)
                val results = db.execute(query.first, query.second)
                val json = JSONObject().success()
                val photoArray = JSONArray()
                json.put("photos", photoArray)

                println("Get Complete Db call ${System.currentTimeMillis() - time}ms")
                time = System.currentTimeMillis()

                Database.processResult(results) {

                    val photoNode = it["photo"] as Node
                    val deleted = photoNode.getProperty("deleted") as Boolean

                    if (getDeleted || !deleted) {
                        val photoObject = JSONObject()
                        photoArray.put(photoObject)

                        photoObject.put("md5", photoNode.getProperty("md5") as String)
                        photoObject.put("bytes", photoNode.getProperty("bytes") as Long)
                        photoObject.put("photo_id", photoNode.getProperty("photo_id") as String)
                        photoObject.put("notes_updated", photoNode.getProperty("notes_updated") as Long)
                        photoObject.put("tags_updated", photoNode.getProperty("tags_updated") as Long)
                        photoObject.put("mime", photoNode.getProperty("mime") as String)
                        photoObject.put("is_video", photoNode.getProperty("is_video") as Boolean)
                        photoObject.put("deleted", deleted)
                        photoObject.put("date_taken", photoNode.getProperty("date_taken") as Long)


                        val folders = it["folders"] as ArrayList<HashMap<String?, String?>>
                        val foldersArray = JSONArray().apply {
                            folders.forEach { folder ->
                                this.put(JSONObject()
                                        .put("filename", folder["filename"])
                                        .put("folderpath", folder["folderpath"]))
                            }
                        }
                        photoObject.put("folders", foldersArray)

                        val tags = it["tags"] as ArrayList<String>
                        photoObject.put("number_tags", tags.size)
                    }

                }

                println("json processing ${System.currentTimeMillis() - time}ms")

                return@executeTransaction json
            } as JSONObject? ?: JSONObject().fail(message = "DB Fail")
        }

        val job1 = GlobalScope.async { doTransaction.invoke() }


        runBlocking {
            val result = job1.await()

            return@runBlocking result
        }

    }


    val completeAlt = Route { request, response ->
        val userID = request.attribute("user") as String
        val getDeleted = request.queryParamOrDefault("deleted", "false")!!.toBoolean()


        var time = System.currentTimeMillis()
        var photoArray = Vector<String>()


        Database.executeTransaction { db ->
            val user = db.findNode({ "User" }, "user_id", userID)
            val photos = user.getRelationships({ "OWNS" }, Direction.OUTGOING)
            val photoCount = photos.count()

            photoArray = Vector<String>(photoCount)


            var count = 0

            fun processNode(photoNode: Node) {
                Database.executeTransaction {
                    val deleted = photoNode.getProperty("deleted") as Boolean

                    if (getDeleted || !deleted) {
                        val photoObject = JSONObject()

                        photoObject.put("md5", photoNode.getProperty("md5") as String)
                        photoObject.put("bytes", photoNode.getProperty("bytes") as Long)
                        photoObject.put("photo_id", photoNode.getProperty("photo_id") as String)
                        photoObject.put("notes_updated", photoNode.getProperty("notes_updated") as Long)
                        photoObject.put("tags_updated", photoNode.getProperty("tags_updated") as Long)
                        photoObject.put("mime", photoNode.getProperty("mime") as String)
                        photoObject.put("is_video", photoNode.getProperty("is_video") as Boolean)
                        photoObject.put("deleted", deleted)
                        photoObject.put("date_taken", photoNode.getProperty("date_taken") as Long)

                        val foldersArray = JSONArray()
                        photoObject.put("folders", foldersArray)
                        photoNode.getRelationships({ "CONTAINS" }, Direction.INCOMING).forEach { r ->
                            val folderNode = r.startNode
                            foldersArray.put(JSONObject()
                                    .put("filename", r.getProperty("filename"))
                                    .put("folderpath", folderNode.getProperty("folderpath")))

                        }

                        val tagCount = photoNode.getRelationships({ "DESCRIBES" }, Direction.INCOMING).count()
                        photoObject.put("number_tags", tagCount)

                        photoArray.add(photoObject.toString())
                    }
                }
            }

            val executorService = Executors.newFixedThreadPool(3)
            val parallelThreshold = photoCount / 4 * 3

            photos.forEach dkal@{ p ->

                val photoNode = p.endNode

                if (count < parallelThreshold) {
                    executorService.execute { processNode(photoNode) }
                } else {
                    processNode(photoNode)
                }

                count++
            }

            executorService.shutdown()
            executorService.awaitTermination(60000, TimeUnit.MILLISECONDS)

            println(photoArray.size)
            println("Count : $count")
            println("Get Complete Db call ${System.currentTimeMillis() - time}ms")

        } ?: return@Route JSONObject().fail(message = "DB Fail")

        time = System.currentTimeMillis()

        val returnString = run {
            val successPrefix = JSONObject().success().toString()

            val sb = StringJoiner(",", successPrefix.substring(0, successPrefix.length - 1) + ",photos:[", "]}")
            photoArray.forEach {
                sb.add(it)
            }
            sb.toString()
        }

        println("Convert String ${System.currentTimeMillis() - time}ms")


        returnString

    }

    private fun metadataQuery(userID: String, photoID: String): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params["user_id"] = userID
        params["photo_id"] = photoID
        return Pair("match (:User{user_id:\$user_id})-[:OWNS]->(p:Photo{photo_id:\$photo_id}), (p)<-[c:CONTAINS]-(f:Folder) optional match (p)<-[:DESCRIBES]-(t:Tag)\n" +
                "return p as photo, collect(distinct {filename:c.filename,folderpath:f.folderpath}) as folders, collect(distinct(t.name)) as tags", params)
    }

    val getMetadata = Route { request, response ->
        val userID = request.attribute("user") as String
        val photoID = request.queryParamOrDefault("photo_id", "null")

        Database.executeTransaction {

            val query = metadataQuery(userID, photoID)
            val results = it.execute(query.first, query.second)
            val json = JSONObject()


            Database.processResult(results) {

                val photoNode = it["photo"] as Node
                val photoObject = JSONObject()
                json.put("photo", photoObject)

                photoObject.put("md5", photoNode.getProperty("md5") as String)
                photoObject.put("bytes", photoNode.getProperty("bytes") as Long)
                photoObject.put("photo_id", photoNode.getProperty("photo_id") as String)
                photoObject.put("notes", photoNode.getProperty("notes") as String)
                photoObject.put("notes_updated", photoNode.getProperty("notes_updated") as Long)
                photoObject.put("tags_updated", photoNode.getProperty("tags_updated") as Long)
                photoObject.put("mime", photoNode.getProperty("mime") as String)
                photoObject.put("is_video", photoNode.getProperty("is_video") as Boolean)
                photoObject.put("deleted", photoNode.getProperty("deleted") as Boolean)
                photoObject.put("date_taken", photoNode.getProperty("date_taken") as Long)


                val folders = it["folders"] as ArrayList<HashMap<String?, String?>>
                val foldersArray = JSONArray().apply {
                    folders.forEach { folder ->
                        this.put(JSONObject()
                                .put("filename", folder["filename"])
                                .put("folderpath", folder["folderpath"]))
                    }
                }
                photoObject.put("folders", foldersArray)

                val tags = it["tags"] as ArrayList<String>
                val tagsArray = JSONArray().apply {
                    tags.forEach { tag ->
                        this.put(tag)
                    }
                }
                photoObject.put("tags", tagsArray)

            }

            if (json.has("photo")) {
                return@executeTransaction json.success()
            } else {
                return@executeTransaction json.fail(message = "Photo not found")
            }
        } as JSONObject? ?: JSONObject().fail(message = "DB Fail")

    }

    private fun uploadQuery(userID: String, md5: String, bytes: Long): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params["user_id"] = userID
        params["md5"] = md5
        params["bytes"] = bytes

        return Pair("match (:User{user_id:\$user_id})-[:OWNS]->(p:Photo{md5:\$md5, bytes:\$bytes})\n" +
                "return p as photo", params)
    }

    private fun sameFileQuery(userID: String, filename: String, folderpath: String): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params["user_id"] = userID
        params["filename"] = filename
        params["folderpath"] = folderpath

        return Pair("match (:User{user_id:\$user_id})-[:OWNS]->(p:Photo)<-[c:CONTAINS{filename:\$filename}]-(f:Folder{folderpath:\$folderpath})\n" +
                "return p as photo", params)
    }

    private fun detachDeleteQuery(userID: String, md5: String, bytes: Long): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params["user_id"] = userID
        params["md5"] = md5
        params["bytes"] = bytes

        return Pair("match (:User{user_id:\$user_id})-[:OWNS]->(p:Photo{md5:\$md5, bytes:\$bytes})\n" +
                "detach delete p", params)
    }

    val upload = Route { request, response ->
        val userID = request.attribute("user") as String


        // handle request manually to prevent spark from reading full file stream into memory first
        val upload = ServletFileUpload()
        var jsonText = "{}"


        val iter = upload.getItemIterator(request.raw());
        while (iter.hasNext()) {
            val item = iter.next()
            val name = item.fieldName;

            if (item.isFormField) {
                val field = Streams.asString(item.openStream())
                println("Form field $name with value $field detected.")

                if(name == "json"){
                    jsonText = field
                }

            } else {

                println("File field $name with file name ${item.name} detected.")
                if(name=="photo"){

                    println(jsonText)
                    val requestJson = JSONObject( jsonText)
                    val reportedMD5 = requestJson.optString("md5")
                    val bytes = requestJson.optLong("bytes")
                    val folderArray = requestJson.optJSONArray("folders") ?: JSONArray()
                    val folders = ArrayList<Pair<String, String>>().apply {
                        folderArray.forEach {
                            val filename = (it as JSONObject).optString("filename")
                            val folderpath = it.optString("folderpath")
                            this.add(Pair(folderpath, filename))
                        }
                    }
                    val tagArray = requestJson.optJSONArray("tags") ?: JSONArray()
                    val tags = ArrayList<String>().apply {
                        tagArray.forEach { tag -> this.add(tag as String) }
                    }
                    val tagsUpdated = requestJson.optLong("tags_updated")
                    val notes = requestJson.optString("notes")
                    val notesUpdated = requestJson.optLong("notes_updated")
                    val dateTaken = requestJson.optLong("date_taken")

                    val mime = requestJson.optString("mime")
                    val isVideo = requestJson.optBoolean("is_video")

                    // check that md5 not registered already
                    var deleted = false
                    var foundNode: Node? = null
                    val count = Database.executeTransaction {
                        val query = uploadQuery(userID, reportedMD5, bytes)
                        val results = it.execute(query.first, query.second)
                        var count = 0

                        Database.processResult(results) {
                            foundNode = it["photo"] as Node
                            deleted = deleted || foundNode!!.getProperty("deleted") as Boolean
                            count++
                        }

                        return@executeTransaction count
                    } as? Int? ?: 0

                    // if match md5 and bytes and not deleted, assume trying to upload duplicate
                    if (count > 0 && !deleted) {
                        return@Route JSONObject().fail(message = "Already exists")
                    }

                    // if matched but deleted, just delete the whole node and reupload
                    if (deleted) {
                        Database.executeTransaction {
                            val query = detachDeleteQuery(userID, reportedMD5, bytes)
                            it.execute(query.first, query.second)
                            foundNode = null
                            return@executeTransaction null
                        }
                    }

                    // if unmatched
                    // check that there isn't a different photo with same filepath - we assume incoming file supersedes it, set to deleted
                    folders.forEach { f ->
                        Database.executeTransaction {
                            val query = sameFileQuery(userID, f.second, f.first)
                            val results = it.execute(query.first, query.second)
                            Database.processResult(results) {
                                val node = it["photo"] as Node
                                node.setProperty("deleted", true)
                            }
                            return@executeTransaction null
                        }
                    }


                    val photoID = UUID.randomUUID().toString()
                    val stream = item.openStream() ?:
                    return@Route JSONObject().fail(message = "Photo filestream not received")

                    val saveFilesResult = Utils.handleImage(userID, photoID, stream, isVideo)

                    if (saveFilesResult.originalFile.exists() && reportedMD5 == MD5.calculateMD5(saveFilesResult.originalFile)) {

                        Database.executeTransaction {
                            val photoNode = foundNode ?: it.createNode(Label { "Photo" })
                            photoNode.setProperty("md5", reportedMD5)
                            photoNode.setProperty("bytes", bytes)
                            photoNode.setProperty("photo_id", photoID)
                            photoNode.setProperty("notes", notes)
                            photoNode.setProperty("notes_updated", notesUpdated)
                            photoNode.setProperty("tags_updated", tagsUpdated)
                            photoNode.setProperty("mime", mime)
                            photoNode.setProperty("date_taken", dateTaken)
                            photoNode.setProperty("is_video", isVideo)
                            photoNode.setProperty("deleted", false)

                            val userNode = it.findNode({ "User" }, "user_id", userID)
                            userNode.createRelationshipTo(photoNode) { "OWNS" }

                            folders.forEach { folderPair ->
                                val folderNode = it.findNode({ "Folder" }, "folderpath", folderPair.first)
                                        ?: it.createNode(Label { "Folder" }).apply {
                                            this.setProperty("folderpath", folderPair.first)
                                        }
                                folderNode.createRelationshipTo(photoNode) { "CONTAINS" }.apply {
                                    this.setProperty("filename", folderPair.second)
                                }
                            }

                            tags.forEach { tag ->
                                val tagNode = it.findNode({ "Tag" }, "name", tag) ?: it.createNode(Label { "Tag" }).apply {
                                    this.setProperty("name", tag.toLowerCase())
                                }
                                tagNode.createRelationshipTo(photoNode) { "DESCRIBES" }
                            }

                        }

                        return@Route JSONObject().success()
                    } else {
                        saveFilesResult.originalFile.delete()
                        saveFilesResult.thumbFile.delete()
                        return@Route JSONObject().fail(message = "Failed to save photo")
                    }


                }

            }
        }

        return@Route JSONObject().fail(message = "Multipart iteration failed")

    }

    private fun deleteQuery(userID: String, photoIDs: List<String>): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params["user_id"] = userID
        params["photo_ids"] = photoIDs
        return Pair("match (p:Photo) where p.photo_id in \$photo_ids and (:User{user_id:\$user_id})-[:OWNS]->(p) set p.deleted=TRUE\n" +
                "return p as photo", params)
    }

    val delete = Route { request, response ->
        val userID = request.attribute("user") as String
        val requestJson = JSONObject(request.body())

        val toDelete = (requestJson.optJSONArray("photos") ?: JSONArray()).mapTo(ArrayList<String>()) {
            it as String
        }

        Database.executeTransaction {
            val query = deleteQuery(userID, toDelete)
            val results = it.execute(query.first, query.second)
            val json = JSONObject()

            val deleted = HashSet<String>()

            Database.processResult(results) {
                val photoNode = it["photo"] as Node
                deleted.add(photoNode.getProperty("photo_id") as String)
            }

            val notDeleted = toDelete.filter { p -> !deleted.contains(p) }


            val success = JSONArray().apply {
                deleted.forEach { p -> this.put(p) }
            }

            val failure = JSONArray().apply {
                notDeleted.forEach { p -> this.put(p) }
            }

            deleted.forEach { photoID ->
                val userFolder = File(Main.picFolder, userID)
                val originalFolder = File(userFolder, "original")
                val thumbFolder = File(userFolder, "thumb")
                val deleteFolder = File(userFolder, "delete").apply { this.mkdirs() }
                val originalPhoto = File(originalFolder, photoID)
                val thumbPhoto = File(thumbFolder, photoID)
                val deletePhoto = File(deleteFolder, photoID)

                thumbPhoto.delete()
                originalPhoto.renameTo(deletePhoto)
            }

            json.put("deleted", success)
            json.put("failure", failure)

            return@executeTransaction json.success()
        } as JSONObject? ?: JSONObject().fail(message = "DB Fail")

    }


    private fun editQuery(userID: String, photoIDs: List<String>): Pair<String, HashMap<String, Any>> {
        val params = hashMapOf<String, Any>()
        params["user_id"] = userID
        params["photo_ids"] = photoIDs
        return Pair("match (p:Photo) where p.photo_id in \$photo_ids and (:User{user_id:\$user_id})-[:OWNS]->(p)\n" +
                "return p as photo", params)
    }

    val edit = Route { request, response ->
        val userID = request.attribute("user") as String
        val requestJson = JSONObject(request.body())

        val editArray = (requestJson.optJSONArray("photos") ?: JSONArray())

        val editList = ArrayList<String>()
        val editMap = HashMap<String, JSONObject>()
        editArray.forEach {
            val obj = it as JSONObject
            val photoID = obj.getString("photo_id")
            editList.add(photoID)
            editMap[photoID] = obj
        }

        Database.executeTransaction { db ->
            val query = editQuery(userID, editList)
            val results = db.execute(query.first, query.second)
            val json = JSONObject()


            Database.processResult(results) {
                val photoNode = it["photo"] as Node
                val photoID = photoNode.getProperty("photo_id") as String
                val jsonObj = editMap[photoID]

                if (jsonObj != null) {

                    if (jsonObj.has("folders")) {
                        val folderArray = jsonObj.optJSONArray("folders") ?: JSONArray()

                        val folderRels = photoNode.getRelationships(RelationshipType { "CONTAINS" })
                        folderRels.iterator().forEach {
                            it.delete()
                        }

                        folderArray.forEach {
                            val filename = (it as JSONObject).optString("filename")
                            val folderpath = it.optString("folderpath")

                            val folderNode = db.findNode({ "Folder" }, "folderpath", folderpath)
                                    ?: db.createNode(Label { "Folder" }).apply {
                                        this.setProperty("folderpath", folderpath)
                                    }
                            folderNode.createRelationshipTo(photoNode) { "CONTAINS" }.apply {
                                this.setProperty("filename", filename)
                            }
                        }

                    }

                    if (jsonObj.has("tags")) {
                        val tagArray = jsonObj.optJSONArray("tags") ?: JSONArray()

                        val tagRels = photoNode.getRelationships(RelationshipType { "DESCRIBES" })
                        tagRels.iterator().forEach {
                            it.delete()
                        }
                        tagArray.forEach { t ->
                            val tag = t as String
                            val tagNode = db.findNode({ "Tag" }, "name", tag) ?: db.createNode(Label { "Tag" }).apply {
                                this.setProperty("name", tag.toLowerCase())
                            }
                            tagNode.createRelationshipTo(photoNode) { "DESCRIBES" }
                        }

                    }

                    if (jsonObj.has("tags_updated")) {
                        photoNode.setProperty("tags_updated", jsonObj.optLong("tags_updated"))
                    }

                    if (jsonObj.has("notes")) {
                        photoNode.setProperty("notes", jsonObj.optString("notes"))
                        photoNode.setProperty("notes_updated", System.currentTimeMillis())
                    }

                    if (jsonObj.has("notes_updated")) {
                        photoNode.setProperty("notes_updated", jsonObj.optLong("notes_updated"))
                    }

                }
            }

            return@executeTransaction json.success()
        } as JSONObject? ?: JSONObject().fail(message = "DB Fail")


    }

}

