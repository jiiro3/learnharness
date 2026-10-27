package com.jiiro.learnharness

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import kotlin.math.max

/**
 * The whole app: a full-screen WebView on the configured LearnHarness server, plus the native
 * setup and error screens drawn over it.
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var errorPanel: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var wakingPanel: View
    private lateinit var setupPanel: View
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var cancelButton: Button

    private val store by lazy { ConfigStore(applicationContext) }
    private var config: ServerConfig? = null
    private var webView: WebView? = null
    private var downloads: Downloads? = null

    /** Main-frame URL whose load failed; Retry loads it again. */
    private var failedUrl: String? = null

    /** Cold-start retries (WakeRetry): attempts used, the scheduled retry, and whether the current load failed. */
    private val handler = Handler(Looper.getMainLooper())
    private var wakeAttempt = 0
    private var pendingWake: Runnable? = null
    private var loadFailed = false
    private var safeInsets = Insets.NONE

    private lateinit var updater: Updater

    private var pendingPermission: PermissionRequest? = null
    private var pendingFiles: ValueCallback<Array<Uri>>? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingPermission ?: return@registerForActivityResult
        pendingPermission = null
        if (granted) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request.deny()
            toast(getString(R.string.mic_denied))
        }
    }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFiles ?: return@registerForActivityResult
        pendingFiles = null
        val data = result.data
        val uris = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> data.clipData!!.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEB_DEBUGGING)

        root = findViewById(R.id.root)
        webContainer = findViewById(R.id.web_container)
        errorPanel = findViewById(R.id.error_panel)
        errorTitle = findViewById(R.id.error_title)
        errorMessage = findViewById(R.id.error_message)
        wakingPanel = findViewById(R.id.waking_panel)
        setupPanel = findViewById(R.id.setup_panel)
        urlInput = findViewById(R.id.setup_url)
        tokenInput = findViewById(R.id.setup_token)
        cancelButton = findViewById(R.id.setup_cancel)
        // A password field defaults to monospace, so its hint looked unlike the URL field's.
        tokenInput.typeface = Typeface.DEFAULT

        setUpInsets()
        setUpBack()
        updater = Updater(
            this,
            findViewById(R.id.update_holder),
            findViewById(R.id.update_text),
            findViewById(R.id.update_install),
            findViewById(R.id.update_later),
        )
        findViewById<Button>(R.id.error_retry).setOnClickListener { retry() }
        findViewById<Button>(R.id.error_settings).setOnClickListener { showSetup(config) }
        findViewById<Button>(R.id.setup_save).setOnClickListener { saveSetup() }
        cancelButton.setOnClickListener { hideSetup() }
        tokenInput.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) saveSetup() }
        }

        val webState = savedInstanceState?.getBundle(STATE_WEB)
        lifecycleScope.launch {
            val saved = store.load()
            if (saved == null) {
                showSetup(null)
            } else {
                openServer(saved, webState)
                if (savedInstanceState == null) handleShortcut(intent, freshLaunch = true)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** Launcher shortcuts (res/xml/shortcuts.xml). A fresh launch is already loading the page. */
    private fun handleShortcut(intent: Intent?, freshLaunch: Boolean = false) {
        when (intent?.action) {
            ACTION_SERVER_SETTINGS -> showSetup(config)
            ACTION_RELOAD -> if (config != null && !freshLaunch) retry()
            ACTION_CHECK_UPDATES -> updater.check(force = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let { web -> outState.putBundle(STATE_WEB, Bundle().also { web.saveState(it) }) }
    }

    override fun onDestroy() {
        cancelWake()
        updater.release()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    // ---- Server and WebView ------------------------------------------------------------------

    /** Shows [cfg] in a fresh WebView, so listeners and scripts are scoped to its origin only. */
    private fun openServer(cfg: ServerConfig, state: Bundle?) {
        config = cfg
        failedUrl = null
        resetWake()
        webView?.let { webContainer.removeView(it); it.destroy() }
        val web = createWebView(cfg)
        webView = web
        downloads = Downloads(this, lifecycleScope, cfg)
        updater.onServer(cfg)
        webContainer.addView(web, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        webContainer.isVisible = true
        errorPanel.isVisible = false
        setupPanel.isVisible = false
        updateBarAppearance()

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        val cookie = cfg.token?.let { ServerUrls.tokenCookie(it, cfg.isHttps) } ?: ServerUrls.clearTokenCookie(cfg.isHttps)
        cookies.setCookie(cfg.origin, cookie) {
            cookies.flush()
            if (web != webView) return@setCookie
            if (state == null || web.restoreState(state) == null) web.loadUrl(cfg.url, authHeaders(cfg, cfg.url))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(cfg: ServerConfig): WebView {
        val web = WebView(this)
        web.setBackgroundColor(getColor(R.color.lh_web_background))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString LearnHarnessAndroid/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = ShellClient()
        web.webChromeClient = ShellChromeClient()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloads?.start(url, userAgent, contentDisposition, mimeType)
        }
        // Blob exports: an origin-scoped message channel, not addJavascriptInterface. Without these
        // features blob exports fall through to the DownloadListener's toast.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val rules = setOf(cfg.origin)
                WebViewCompat.addWebMessageListener(web, WebScripts.DOWNLOAD_BRIDGE, rules) { _, message, _, isMainFrame, _ ->
                    if (isMainFrame) message.data?.let { downloads?.saveBlob(it) }
                }
                WebViewCompat.addDocumentStartJavaScript(web, WebScripts.BLOB_DOWNLOADS, rules)
            }
        }
        return web
    }

    private fun authHeaders(cfg: ServerConfig, url: String): Map<String, String> {
        val token = cfg.token ?: return emptyMap()
        return if (ServerUrls.sameOrigin(url, cfg.origin)) mapOf("Authorization" to "Bearer $token") else emptyMap()
    }

    private fun retry() {
        val cfg = config ?: return
        val web = webView
        errorPanel.isVisible = false
        resetWake()
        updateBarAppearance()
        when {
            web == null -> openServer(cfg, null)
            failedUrl != null -> failedUrl!!.let { url ->
                failedUrl = null
                web.loadUrl(url, authHeaders(cfg, url))
            }
            else -> web.reload()
        }
    }

    private fun showError(message: String, title: String = getString(R.string.error_title)) {
        resetWake()
        errorTitle.text = title
        errorMessage.text = message
        errorPanel.isVisible = true
        updateBarAppearance()
    }

    /**
     * A main-frame load of [url] failed the way a cold start does: show "Waking up the server…" and
     * load it again after the next backoff delay, or show [finalError] once retries run out.
     */
    private fun wakeOrFail(url: String, finalError: () -> String) {
        loadFailed = true
        failedUrl = url
        val delay = WakeRetry.delayMs(wakeAttempt)
        if (delay == null) {
            showError(finalError())
            return
        }
        wakeAttempt++
        cancelWake()
        errorPanel.isVisible = false
        wakingPanel.isVisible = true
        updateBarAppearance()
        val job = Runnable {
            pendingWake = null
            val cfg = config ?: return@Runnable
            webView?.loadUrl(url, authHeaders(cfg, url))
        }
        pendingWake = job
        handler.postDelayed(job, delay)
    }

    private fun cancelWake() {
        pendingWake?.let(handler::removeCallbacks)
        pendingWake = null
    }

    private fun resetWake() {
        cancelWake()
        wakeAttempt = 0
        wakingPanel.isVisible = false
    }

    private fun isOnline(): Boolean = getSystemService(ConnectivityManager::class.java)?.activeNetwork != null

    private fun injectInsets(web: WebView) {
        val cfg = config ?: return
        if (web.url?.let { ServerUrls.sameOrigin(it, cfg.origin) } != true) return
        val d = resources.displayMetrics.density
        val i = safeInsets
        web.evaluateJavascript(WebScripts.safeAreaInsets(i.top / d, i.right / d, i.bottom / d, i.left / d), null)
    }

    private fun openExternal(uri: Uri) {
        try {
            if (uri.scheme == "http" || uri.scheme == "https") {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
                    .build()
                    .launchUrl(this, uri)
            } else {
                val intent = if (uri.scheme == "intent") {
                    // Only the intent's data and action: never an explicit component from a page.
                    Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                        component = null
                        selector = null
                    }
                } else {
                    Intent(Intent.ACTION_VIEW, uri)
                }
                startActivity(intent.addCategory(Intent.CATEGORY_BROWSABLE))
            }
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.no_app_for_link))
        } catch (_: java.net.URISyntaxException) {
            toast(getString(R.string.no_app_for_link))
        }
    }

    // Lint misses the onRenderProcessGone override below.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class ShellClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val cfg = config ?: return true
            return when (request.url.scheme?.lowercase()) {
                "http", "https" -> if (ServerUrls.sameOrigin(request.url.toString(), cfg.origin)) {
                    false
                } else {
                    openExternal(request.url)
                    true
                }
                "about", "blob", "data" -> false
                else -> {
                    openExternal(request.url)
                    true
                }
            }
        }

        /** Enforces the cleartext rule the network security config can't express (IP ranges). */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (ServerUrls.cleartextAllowed(request.url.toString())) return null
            return WebResourceResponse("text/plain", "utf-8", 403, "Cleartext blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (view != webView) return
            failedUrl = null
            loadFailed = false
        }

        override fun onPageCommitVisible(view: WebView, url: String) = injectInsets(view)

        override fun onPageFinished(view: WebView, url: String) {
            if (view == webView && !loadFailed && pendingWake == null) {
                if (wakingPanel.isVisible) {
                    resetWake()
                    updateBarAppearance()
                }
                // After the first good load, so a cold-starting server serves the page first.
                updater.check(force = false)
            }
                injectInsets(view)
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view != webView || !request.isForMainFrame) return
            val url = request.url.toString()
            val host = request.url.host ?: url
            val message = { getString(R.string.error_unreachable, host, error.description) }
            failedUrl = url
            loadFailed = true
            when {
                !isOnline() -> showError(getString(R.string.error_offline))
                error.errorCode in TRANSIENT_ERRORS ->
                    wakeOrFail(url, message)
                else -> showError(message())
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (view != webView || !request.isForMainFrame) return
            val url = request.url.toString()
            val status = response.statusCode
            when {
                WakeRetry.isAuthStatus(status) -> {
                    failedUrl = url
                    loadFailed = true
                    if (config?.token == null) {
                        showError(getString(R.string.error_no_token, status), getString(R.string.error_no_token_title))
                    } else {
                        showError(getString(R.string.error_auth, status), getString(R.string.error_auth_title))
                    }
                }
                WakeRetry.isWakingStatus(status) ->
                    wakeOrFail(url) { getString(R.string.error_server_status, request.url.host ?: url, status) }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (view != webView) return
            failedUrl = error.url
            showError(getString(R.string.error_tls, error.url.toUri().host ?: error.url))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view == webView) {
                webView = null
                webContainer.removeView(view)
                showError(getString(R.string.error_crashed))
            } else {
                webContainer.removeView(view)
            }
            view.destroy()
            return true
        }
    }

    private inner class ShellChromeClient : WebChromeClient() {
        // On an http origin the page isn't a secure context, so navigator.mediaDevices is missing
        // and the web app shows its own dictation error; no request reaches this method.
        override fun onPermissionRequest(request: PermissionRequest) {
            val cfg = config
            val wantsMic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
            if (cfg == null || !wantsMic || !ServerUrls.sameOrigin(request.origin.toString(), cfg.origin)) {
                request.deny()
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pendingPermission?.deny()
                pendingPermission = request
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermission == request) pendingPermission = null
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            pendingFiles?.onReceiveValue(null)
            pendingFiles = filePathCallback
            return try {
                fileChooser.launch(fileIntent(params))
                true
            } catch (_: ActivityNotFoundException) {
                pendingFiles = null
                false
            }
        }
    }

    /**
     * The page's accept list as a document picker. Extensions such as ".md" have no reliable
     * MIME type (Drive and Files often report text/plain or octet-stream), so any extension in the
     * list means no type filter at all.
     */
    private fun fileIntent(params: WebChromeClient.FileChooserParams): Intent {
        val accepts = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val mimes = if (accepts.isEmpty() || accepts.any { it.startsWith('.') }) emptyList() else accepts
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimes.singleOrNull() ?: "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    // ---- Setup screen ------------------------------------------------------------------------

    private fun showSetup(current: ServerConfig?) {
        urlInput.setText(current?.url.orEmpty())
        tokenInput.setText(current?.token.orEmpty())
        urlInput.error = null
        tokenInput.error = null
        cancelButton.isVisible = current != null
        setupPanel.isVisible = true
        updateBarAppearance()
        urlInput.requestFocus()
    }

    private fun hideSetup() {
        hideKeyboard()
        setupPanel.isVisible = false
        updateBarAppearance()
    }

    private fun saveSetup() {
        val url = ServerUrls.normalize(urlInput.text.toString())
        if (url == null) {
            urlInput.error = getString(R.string.setup_invalid_url)
            return
        }
        if (!ServerUrls.cleartextAllowed(url)) {
            urlInput.error = getString(R.string.setup_cleartext)
            return
        }
        val token = tokenInput.text.toString().trim().ifEmpty { null }
        if (token != null && !ServerUrls.isValidToken(token)) {
            tokenInput.error = getString(R.string.setup_invalid_token)
            return
        }
        val next = ServerConfig(url, token)
        lifecycleScope.launch {
            store.save(next)
            hideSetup()
            if (next != config || webView == null) openServer(next, null)
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ---- Window --------------------------------------------------------------------------------

    /**
     * Edge-to-edge: the page draws under the system bars and gets their size as safe-area insets.
     * The keyboard shrinks the content by its height (what adjustResize did before edge-to-edge).
     */
    private fun setUpInsets() {
        val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(bottom = ime.bottom)
            val safe = insets.getInsets(bars)
            safeInsets = Insets.of(safe.left, safe.top, safe.right, max(0, safe.bottom - ime.bottom))
            webView?.let(::injectInsets)
            insets.inset(0, 0, 0, ime.bottom)
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.update_holder)) { view, insets ->
            val safe = insets.getInsets(bars)
            view.setPadding(safe.left, 0, safe.right, safe.bottom)
            insets
        }
        for (panel in listOf(errorPanel, setupPanel, wakingPanel)) {
            ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
                val safe = insets.getInsets(bars)
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
    }

    /** The web app is always dark; the native screens follow the system theme. */
    private fun updateBarAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val light = !night && (setupPanel.isVisible || errorPanel.isVisible || webView == null)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun setUpBack() {
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val web = webView
            val cfg = config
            when {
                setupPanel.isVisible && cfg != null -> hideSetup()
                // The page first closes its own top overlay (sheet, settings, menu); only when it
                // has none does Back move through history or leave the app.
                web != null && cfg != null && !errorPanel.isVisible && !wakingPanel.isVisible &&
                    web.url?.let { ServerUrls.sameOrigin(it, cfg.origin) } == true ->
                    web.evaluateJavascript(WebScripts.BACK) { handled ->
                        if (handled != "true" && web == webView) navigateBack(web)
                    }
                else -> navigateBack(web)
            }
        }
    }

    /** Back with nothing left to close in the page: WebView history, then leave the app. */
    private fun navigateBack(web: WebView?) {
        if (web != null && web.canGoBack()) {
            errorPanel.isVisible = false
            updateBarAppearance()
            web.goBack()
        } else {
            backCallback.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            backCallback.isEnabled = true
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val ACTION_SERVER_SETTINGS = "com.jiiro.learnharness.action.SERVER_SETTINGS"
        const val ACTION_RELOAD = "com.jiiro.learnharness.action.RELOAD"
        const val ACTION_CHECK_UPDATES = "com.jiiro.learnharness.action.CHECK_UPDATES"
        const val STATE_WEB = "web"

        /** Load errors a cold-starting server causes; others (DNS, TLS, bad URL) fail at once. */
        val TRANSIENT_ERRORS = setOf(WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO)
    }
}
