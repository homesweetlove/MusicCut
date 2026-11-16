package com.musiccut.app.drive

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DriveFile(val id: String, val name: String, val webViewLink: String?)

/** Minimal Google Drive v3 REST client: find/create an app folder and resumable-upload files. */
class DriveUploader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build(),
) {
    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    fun upload(
        token: String,
        file: File,
        name: String,
        mimeType: String,
        folderName: String = DEFAULT_FOLDER,
        progress: (Float) -> Unit = {},
    ): DriveFile {
        val folderId = findOrCreateFolder(token, folderName)
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", mimeType)
            .put("parents", JSONArray().put(folderId))

        val init = Request.Builder()
            .url("$UPLOAD_URL?uploadType=resumable&fields=id,name,webViewLink")
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toString().toRequestBody(jsonType))
            .build()
        val session = client.newCall(init).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Drive upload init failed: ${resp.code} ${resp.body?.string()}")
            resp.header("Location") ?: throw IOException("Drive did not return an upload URL")
        }

        val put = Request.Builder()
            .url(session)
            .header("Authorization", "Bearer $token")
            .put(ProgressFileBody(file, mimeType.toMediaType(), progress))
            .build()
        client.newCall(put).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Drive upload failed: ${resp.code} $body")
            val json = JSONObject(body)
            return DriveFile(json.getString("id"), json.optString("name", name), json.optString("webViewLink").ifEmpty { null })
        }
    }

    private fun findOrCreateFolder(token: String, folderName: String): String {
        val escaped = folderName.replace("\\", "\\\\").replace("'", "\\'")
        val q = "mimeType='$FOLDER_MIME' and name='$escaped' and trashed=false"
        val search = Request.Builder()
            .url("$FILES_URL?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id,name)&spaces=drive")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(search).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Drive folder lookup failed: ${resp.code} $body")
            val files = JSONObject(body).optJSONArray("files")
            if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")
        }
        val create = Request.Builder()
            .url("$FILES_URL?fields=id")
            .header("Authorization", "Bearer $token")
            .post(JSONObject().put("name", folderName).put("mimeType", FOLDER_MIME).toString().toRequestBody(jsonType))
            .build()
        client.newCall(create).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Drive folder create failed: ${resp.code} $body")
            return JSONObject(body).getString("id")
        }
    }

    private class ProgressFileBody(
        private val file: File,
        private val type: MediaType,
        private val progress: (Float) -> Unit,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1)
            var sent = 0L
            file.source().use { src ->
                while (true) {
                    val n = src.read(sink.buffer, 64 * 1024)
                    if (n < 0) break
                    sink.flush()
                    sent += n
                    progress(sent.toFloat() / total)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_FOLDER = "MusicCut AI"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
