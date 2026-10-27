package com.jiiro.learnharness

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What `GET {server}/api/android/latest` publishes. */
data class PublishedApk(val versionCode: Long, val versionName: String, val sha256: String, val size: Long) {
    companion object {
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /** Parses the endpoint's JSON; null if any field is missing or malformed. */
        fun parse(json: String): PublishedApk? = try {
            val o = JSONObject(json)
            val apk = PublishedApk(
                versionCode = o.getLong("versionCode"),
                versionName = o.getString("versionName"),
                sha256 = o.getString("sha256").lowercase(),
                size = o.getLong("size"),
            )
            apk.takeIf { it.versionCode > 0 && it.size > 0 && it.versionName.isNotBlank() && SHA256_HEX.matches(it.sha256) }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * In-app updates from the configured server. Checks at most every [CHECK_INTERVAL_MS] after the
 * first page load (never before it, so a cold-starting server serves the page first), offers a
 * newer versionCode in a small bar, and installs it through a PackageInstaller session after
 * checking its size, SHA-256, package name and versionCode.
 */
class Updater(
    private val activity: ComponentActivity,
    private val holder: View,
    private val text: TextView,
    private val install: Button,
    private val later: Button,
) {
    private val prefs = activity.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var config: ServerConfig? = null
    private var offered: PublishedApk? = null
    private var checking: Job? = null
    private var installing: Job? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onInstallStatus(intent)
    }

    init {
        install.setOnClickListener { offered?.let(::install) }
        later.setOnClickListener { hide() }
        activity.registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL_STATUS), Context.RECEIVER_NOT_EXPORTED)
    }

    fun release() = activity.unregisterReceiver(statusReceiver)

    /** A (new) server is shown: forget any offer for the previous one. */
    fun onServer(cfg: ServerConfig) {
        if (cfg != config) {
            checking?.cancel()
            installing?.cancel()
            hide()
        }
        config = cfg
    }

    /** Checks unless one ran for this server in the last 6 h; [force] (the shortcut) always checks and reports. */
    fun check(force: Boolean) {
        val cfg = config ?: return
        if (checking?.isActive == true || installing?.isActive == true) return
        val key = "last_check:${cfg.origin}"
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(key, 0) in 0 until CHECK_INTERVAL_MS) return
        checking = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchLatest(cfg) } }
            if (cfg != config) return@launch
            result.onSuccess { latest ->
                prefs.edit { putLong(key, System.currentTimeMillis()) }
                when {
                    latest != null && latest.versionCode > BuildConfig.VERSION_CODE -> offer(latest)
                    force -> toast(activity.getString(R.string.update_current, BuildConfig.VERSION_NAME))
                }
            }.onFailure {
                if (force) toast(activity.getString(R.string.update_check_failed))
            }
        }
    }

    private fun offer(apk: PublishedApk) {
        offered = apk
        show(activity.getString(R.string.update_available, apk.versionName), buttons = true)
    }

    private fun install(apk: PublishedApk) {
        val cfg = config ?: return
        if (installing?.isActive == true) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            show(activity.getString(R.string.update_allow_installs), buttons = true)
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()),
            )
            return
        }
        show(activity.getString(R.string.update_downloading, apk.versionName), buttons = false)
        installing = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = download(cfg, apk)
                    checkArchive(file, apk)
                    commit(file)
                }
            }
            result.onSuccess {
                show(activity.getString(R.string.update_installing, apk.versionName), buttons = false)
            }.onFailure { error ->
                show(activity.getString(R.string.update_failed, error.message ?: error.javaClass.simpleName), buttons = true)
            }
        }
    }

    private fun onInstallStatus(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // The system's "Update this app?" confirmation; our own receiver, so the intent is the system's.
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let(activity::startActivity)
            PackageInstaller.STATUS_SUCCESS -> hide() // The app is replaced and restarted.
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                show(activity.getString(R.string.update_failed, message), buttons = true)
            }
        }
    }

    private fun fetchLatest(cfg: ServerConfig): PublishedApk? {
        val connection = open(cfg, "${cfg.url}/api/android/latest")
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.use { it.readNBytes(MAX_MANIFEST_BYTES) }.toString(Charsets.UTF_8)
                    PublishedApk.parse(body) ?: throw IOException("malformed update manifest")
                }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads into the app cache, checking the advertised size and SHA-256 while streaming. */
    private fun download(cfg: ServerConfig, apk: PublishedApk): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "learnharness-${apk.versionCode}.apk")
        val connection = open(cfg, "${cfg.url}/api/android/apk")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > apk.size) throw IOException("download is larger than ${apk.size} bytes")
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (total != apk.size) throw IOException("downloaded $total of ${apk.size} bytes")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != apk.sha256) throw IOException("SHA-256 mismatch")
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** The APK must be this app at the advertised version; Android checks the signature on install. */
    private fun checkArchive(file: File, apk: PublishedApk) {
        val info = activity.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw IOException("not a valid APK")
        if (info.packageName != activity.packageName) throw IOException("APK is for ${info.packageName}")
        if (info.longVersionCode != apk.versionCode) throw IOException("APK is version ${info.longVersionCode}, not ${apk.versionCode}")
    }

    private fun commit(file: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // Mutable so the installer can add its status extras; explicit to this package.
                val status = Intent(ACTION_INSTALL_STATUS).setPackage(activity.packageName)
                val pending = PendingIntent.getBroadcast(
                    activity, sessionId, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        } finally {
            file.delete()
        }
    }

    /**
     * GET [url] with the Bearer token, following up to three redirects by hand so the token is
     * only ever sent to the server's own origin (a redirect to, say, a signed storage URL gets none).
     */
    private fun open(cfg: ServerConfig, url: String): HttpURLConnection {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            if (!ServerUrls.cleartextAllowed(target)) throw IOException("plain http to $target is not allowed")
            val connection = URL(target).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            val token = cfg.token
            if (token != null && ServerUrls.sameOrigin(target, cfg.origin)) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            val code = connection.responseCode
            if (code !in 300..399 || code == HttpURLConnection.HTTP_NOT_MODIFIED) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            target = URL(URL(target), location ?: throw IOException("HTTP $code without Location")).toString()
        }
        throw IOException("too many redirects")
    }

    private fun show(message: String, buttons: Boolean) {
        text.text = message
        install.isVisible = buttons
        later.isVisible = buttons
        holder.isVisible = true
    }

    private fun hide() {
        holder.isVisible = false
        offered = null
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val MAX_MANIFEST_BYTES = 16 * 1024
        const val MAX_REDIRECTS = 3
        const val ACTION_INSTALL_STATUS = "com.jiiro.learnharness.action.INSTALL_STATUS"
    }
}
