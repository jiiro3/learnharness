package com.jiiro.learnharness

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Downloads from the page: http(s) through DownloadManager, blob: exports through MediaStore. */
class Downloads(private val context: Context, private val scope: CoroutineScope, private val config: ServerConfig) {

    fun start(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val uri = url.toUri()
        if (!ServerUrls.cleartextAllowed(url)) {
            toast(context.getString(R.string.download_failed))
            return
        }
        if (uri.scheme != "http" && uri.scheme != "https") {
            // blob: links are handled by WebScripts.BLOB_DOWNLOADS; anything reaching here was not.
            toast(context.getString(R.string.download_unsupported))
            return
        }
        val name = safeName(URLUtil.guessFileName(url, contentDisposition, mimeType))
        val request = DownloadManager.Request(uri)
            .setTitle(name)
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        userAgent?.let { request.addRequestHeader("User-Agent", it) }
        val token = config.token
        if (token != null && ServerUrls.sameOrigin(url, config.origin)) {
            request.addRequestHeader("Authorization", "Bearer $token")
        }
        try {
            context.getSystemService(DownloadManager::class.java).enqueue(request)
            toast(context.getString(R.string.download_started, name))
        } catch (_: Exception) {
            toast(context.getString(R.string.download_failed))
        }
    }

    /** A message from WebScripts.BLOB_DOWNLOADS: {name, mime, data (base64)}. */
    fun saveBlob(message: String) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    val json = JSONObject(message)
                    val mime = json.optString("mime").ifEmpty { "application/octet-stream" }
                    val name = safeName(json.optString("name").ifEmpty { URLUtil.guessFileName(null, null, mime) })
                    val bytes = Base64.decode(json.getString("data"), Base64.DEFAULT)
                    writeToDownloads(name, mime, bytes)
                    name
                } catch (_: Exception) {
                    null
                }
            }
            toast(
                if (saved != null) context.getString(R.string.download_saved, saved)
                else context.getString(R.string.download_failed),
            )
        }
    }

    private fun writeToDownloads(name: String, mime: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val item = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(resolver.openOutputStream(item)).use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(item, values, null, null)
        } catch (e: Exception) {
            resolver.delete(item, null, null)
            throw e
        }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifEmpty { "download" }
}
