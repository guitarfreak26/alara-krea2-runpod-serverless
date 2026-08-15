package com.alara.hermes.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Download-and-keep for media Hermes sends. Auth headers are attached by the
 * caller only for gateway-host URLs, mirroring MediaGallery's rule so the
 * bearer key never travels to third-party hosts.
 */
object MediaSaver {

    private val client = OkHttpClient()

    private fun fileNameOf(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { "hermes-media" }

    private suspend fun download(url: String, headers: Map<String, String>): Pair<ByteArray, String?> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("empty body")
                body.bytes() to response.header("Content-Type")?.substringBefore(';')?.trim()
            }
        }

    private fun guessMime(name: String, contentType: String?): String {
        contentType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { return it }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    /** Save to the device gallery/downloads. Returns the display name saved. */
    suspend fun saveToGallery(context: Context, url: String, headers: Map<String, String>): Result<String> =
        runCatching {
            if (Build.VERSION.SDK_INT < 29) error("Saving needs Android 10+")
            val name = fileNameOf(url)
            val (bytes, contentType) = download(url, headers)
            val mime = guessMime(name, contentType)
            val (collection, relativePath) = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Hermes"
                mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Hermes"
                mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Hermes"
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Hermes"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val item = resolver.insert(collection, values) ?: error("could not create media entry")
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(item)?.use { it.write(bytes) } ?: error("could not open stream")
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(item, values, null, null)
            name
        }

    /** Download to cache and open the system share sheet with the real file. */
    suspend fun shareFile(context: Context, url: String, headers: Map<String, String>): Result<Unit> =
        runCatching {
            val name = fileNameOf(url)
            val (bytes, contentType) = download(url, headers)
            val mime = guessMime(name, contentType)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, name)
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
}
