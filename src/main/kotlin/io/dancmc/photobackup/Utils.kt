package io.dancmc.photobackup

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.TokenExpiredException
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import de.mkammerer.argon2.Argon2Factory
import org.imgscalr.Scalr
import org.imgscalr.Scalr.Rotation
import org.json.JSONObject
import spark.Request
import java.awt.image.BufferedImage
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*
import javax.imageio.ImageIO
import kotlin.collections.ArrayList
import java.io.InputStreamReader
import java.io.BufferedReader




object Utils {

    object Password {
        val argon2 = Argon2Factory.create()

        fun hashPassword(password: String): String {
            return argon2.hash(1, 65536, 1, password)
        }

        fun verifyPassword(hash: String, password: String): Boolean {
            val result = argon2.verify(hash, password)
            argon2.wipeArray(password.toCharArray())
            return result
        }
    }

    fun loadTSV(file: String): ArrayList<List<String>> {
        val result = ArrayList<List<String>>()

        try {
            BufferedReader(FileReader(File(file))).use { br ->
                var line = br.readLine()

                while (line != null) {
                    result.add(line.split("\t"))
                    line = br.readLine()
                }

            }
        } catch (e: Exception) {
            println(e.message)
        }
        return result
    }

    fun loadTSVLinked(file: String): LinkedList<List<String>> {
        val result = LinkedList<List<String>>()

        try {
            BufferedReader(FileReader(File(file))).use { br ->
                var line = br.readLine()

                while (line != null) {
                    result.add(line.split("\t"))
                    line = br.readLine()
                }

            }
        } catch (e: Exception) {
            println(e.message)
        }
        return result
    }

    fun loadFile(file: String): ArrayList<String> {
        val result = ArrayList<String>()

        try {
            BufferedReader(FileReader(File(file))).use { br ->
                var line = br.readLine()

                while (line != null) {
                    result.add(line)
                    line = br.readLine()
                }

            }
        } catch (e: Exception) {
            println(e.message)
        }
        return result
    }

    fun write(file: String, lines: ArrayList<String>) {
        FileWriter(File(file), true).use { writer ->
            lines.forEach {
                writer.appendln(it)
            }
        }
    }


    fun deepCopy(bi: BufferedImage): BufferedImage {
        val cm = bi.colorModel
        val isAlphaPremultiplied = cm.isAlphaPremultiplied
        val raster = bi.copyData(bi.raster.createCompatibleWritableRaster())
        return BufferedImage(cm, raster, isAlphaPremultiplied, null)
    }

    fun handleImage(userID: String, photoID:String, inputstream: InputStream, isVideo:Boolean):UploadResult{

        val userFolder = File(Main.picFolder, userID)
        val originalFolder = File(userFolder, "original")
        val thumbFolder = File(userFolder, "thumb")

        val originalPhotoFile = File(originalFolder, photoID)
        val thumbPhotoFile = File(thumbFolder, photoID)

        thumbFolder.mkdirs()
        originalFolder.mkdirs()

        inputstream.use { // getPart needs to use same "name" as input field in form
            input ->
            try {
                 Files.copy(input, originalPhotoFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }catch(e:Exception){
                Thread.sleep(100)
                if(originalPhotoFile.exists()){
                    originalPhotoFile.delete()
                }
                println(e.message)
                return UploadResult(false, originalPhotoFile, thumbPhotoFile)
            }
        }


        if (isVideo){

            println("video")
            val thumbPhotoFileWithExt = File(thumbPhotoFile.path+".jpg")
            val r = Runtime.getRuntime()
            r.exec("ffmpeg -ss 00:00:01.000 -i ${originalPhotoFile.path}  -y -vframes 1 ${thumbPhotoFileWithExt.path}")

            var tries = 10
            while (!thumbPhotoFileWithExt.exists() && tries>0){
                Thread.sleep(200)
                tries--
            }

            // ffmpeg takes cue from thumbnail extension, but we don't need ext so we remove it after
            if(thumbPhotoFileWithExt.exists()) {
                thumbPhotoFileWithExt.renameTo(thumbPhotoFile)
            }

        }else {

            var tries = 15
            while (!originalPhotoFile.exists() && tries>0){
                Thread.sleep(100)
                tries--
            }

            val r = Runtime.getRuntime()
            val p = r.exec("convert ${originalPhotoFile.path} -quality 75 -auto-orient -thumbnail 200x200 ${thumbPhotoFile.path}")
            var input = BufferedReader(InputStreamReader(p.inputStream))
            var line = input.readLine()
            while (line != null) {
                System.out.println(line)
                line = input.readLine()
            }
            input.close()

            input = BufferedReader(InputStreamReader(p.errorStream))
            line = input.readLine()
            while (line != null) {
                println(line)
                line = input.readLine()
            }
            input.close()


//            val originalImage = ImageIO.read(originalPhotoFile)
//            var scaledImg = Scalr.resize(originalImage, 200)
//
//            // ---- Begin orientation handling ----
//            val metadata = ImageMetadataReader.readMetadata(originalPhotoFile)
//            val exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
//
//            var orientation = 1
//            try {
//                orientation = exifIFD0Directory.getInt(ExifIFD0Directory.TAG_ORIENTATION)
//            } catch (ex: Exception) {
//                println("no orientation data")
//            }
//
//
//            when (orientation) {
//                1 -> {
//                }
//                2 // Flip X
//                -> scaledImg = Scalr.rotate(scaledImg, Rotation.FLIP_HORZ)
//                3 // PI rotation
//                -> scaledImg = Scalr.rotate(scaledImg, Rotation.CW_180)
//                4 // Flip Y
//                -> scaledImg = Scalr.rotate(scaledImg, Rotation.FLIP_VERT)
//                5 // - PI/2 and Flip X
//                -> {
//                    scaledImg = Scalr.rotate(scaledImg, Rotation.CW_90)
//                    scaledImg = Scalr.rotate(scaledImg, Rotation.FLIP_HORZ)
//                }
//                6 // -PI/2 and -width
//                -> scaledImg = Scalr.rotate(scaledImg, Rotation.CW_90)
//                7 // PI/2 and Flip
//                -> {
//                    scaledImg = Scalr.rotate(scaledImg, Rotation.CW_90)
//                    scaledImg = Scalr.rotate(scaledImg, Rotation.FLIP_VERT)
//                }
//                8 // PI / 2
//                -> scaledImg = Scalr.rotate(scaledImg, Rotation.CW_270)
//                else -> {
//                }
//            }
//            // ---- End orientation handling ----
//
//            ImageIO.write(scaledImg, "jpeg", thumbPhotoFile)
        }

        return UploadResult(true, originalPhotoFile, thumbPhotoFile)
    }

    object Token {
        private val appTokenSecret = "\\x95d\\x9c\\xdc:\\xa8\\xb098\\x13|\\xfb\\xcb\\x86\\x00\\xde\\x83\\xb4\\xc1.\\xfdQ\\xf7{"

        private var algorithmHSAppToken = Algorithm.HMAC256(appTokenSecret)

        private var verifierAppToken = JWT.require(algorithmHSAppToken)
                .withIssuer("dancmc")
                .build() //Reusable verifier instance


        fun createAppToken(userID: String): String {
            return JWT.create()
                    .withIssuer("dancmc")
                    .withIssuedAt(Date(System.currentTimeMillis()))
                    .withAudience(userID)
                    .withExpiresAt(Date(System.currentTimeMillis() + 2629746000L * 12))
                    .sign(algorithmHSAppToken)
        }


        fun verifyAppToken(appToken: String): Any {

            try {
                return verifierAppToken.verify(appToken)
            } catch (e: TokenExpiredException) {
                return "Expired token"
            } catch (e: Exception) {
                return "Failed to decode token"
            }

        }

    }


    fun readDimensions(file: File): Pair<Int, Int> {
        try {
            ImageIO.createImageInputStream(file).use { `in` ->
                val readers = ImageIO.getImageReaders(`in`)
                if (readers.hasNext()) {
                    val reader = readers.next()
                    try {
                        reader.input = `in`
                        return Pair(reader.getWidth(0), reader.getHeight(0))
                    } finally {
                        reader.dispose()
                    }
                }
            }
        } catch (e: Exception) {
            println(file.path)
        }
        return Pair(0, 0)
    }

}

fun Request.decodeToken(): Any {

    val headerToken = this.headers("Authorization") ?: ""

    val decodedAppToken = Utils.Token.verifyAppToken(headerToken)

    when {
        decodedAppToken is String -> return JSONObject().fail(message = decodedAppToken)
        else -> return decodedAppToken
    }
}

fun JSONObject.fail(code: Int = -1, message: String = ""): JSONObject {
    return this
            .put("success", false)
            .put("error_code", code)
            .put("error_message", message)
}

fun JSONObject.success(): JSONObject {
    return this.put("success", true)
}

fun ClosedRange<Int>.random() =
        Random().nextInt((endInclusive + 1) - start) + start

