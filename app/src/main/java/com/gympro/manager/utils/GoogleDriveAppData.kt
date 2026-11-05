package com.gympro.manager.utils

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * عميل Google Drive v3 مصغّر عبر HTTPS مباشرة (بلا مكتبات ثقيلة) لمجلد appDataFolder فقط:
 * مساحة مخفية خاصة بالتطبيق داخل Drive المستخدم (لا تظهر في ملفاته ولا يصل إليها أي تطبيق آخر).
 * النطاق المطلوب drive.appdata غير حسّاس في Google فلا يحتاج مراجعة OAuth خاصة.
 */
object GoogleDriveAppData {
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

    class HttpError(val code: Int, message: String) : IOException("HTTP $code: $message")

    private fun open(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun fail(conn: HttpURLConnection): Nothing {
        val body = runCatching { conn.errorStream?.bufferedReader()?.readText()?.take(200) }.getOrNull().orEmpty()
        throw HttpError(conn.responseCode, body)
    }

    /** اسم الملف ← معرّفه لكل ملفات التطبيق في appDataFolder. */
    fun list(token: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var pageToken: String? = null
        do {
            val suffix = pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val conn = open("$API/files?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files(id,name)$suffix", "GET", token)
            try {
                if (conn.responseCode != 200) fail(conn)
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val files = json.optJSONArray("files")
                if (files != null) for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    result.putIfAbsent(f.getString("name"), f.getString("id"))
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
            } finally {
                conn.disconnect()
            }
        } while (pageToken != null)
        return result
    }

    fun upload(token: String, name: String, file: File, mime: String = "image/jpeg"): String {
        val boundary = "gympro_" + System.nanoTime()
        val metadata = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString()
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: $mime\r\n\r\n"
        val tail = "\r\n--$boundary--"
        val bytes = file.readBytes()
        val headBytes = head.toByteArray(Charsets.UTF_8)
        val tailBytes = tail.toByteArray(Charsets.UTF_8)
        val conn = open("$UPLOAD/files?uploadType=multipart&fields=id", "POST", token)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            conn.setFixedLengthStreamingMode(headBytes.size + bytes.size + tailBytes.size)
            conn.outputStream.use { it.write(headBytes); it.write(bytes); it.write(tailBytes) }
            if (conn.responseCode != 200) fail(conn)
            return JSONObject(conn.inputStream.bufferedReader().readText()).getString("id")
        } finally {
            conn.disconnect()
        }
    }

    fun download(token: String, fileId: String, dest: File) {
        val conn = open("$API/files/$fileId?alt=media", "GET", token)
        try {
            if (conn.responseCode != 200) fail(conn)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException("rename_failed") }
        } finally {
            conn.disconnect()
        }
    }

    fun delete(token: String, fileId: String) {
        val conn = open("$API/files/$fileId", "DELETE", token)
        try {
            val code = conn.responseCode
            if (code != 204 && code != 404) fail(conn)
        } finally {
            conn.disconnect()
        }
    }
}
