package com.jiiro.learnharness

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.MotionEvent
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The whole app: a full-screen WebView on the configured LearnHarness server, plus the native
 * setup and error screens drawn over it.
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var errorPanel: View
    private lateinit var errorMessage: TextView
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
    private var safeInsets = Insets.NONE

    /** Set per touch by WebScripts.canPullToRefresh; false until the page answers. */
    private var pullAllowed = false

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
        swipe = findViewById(R.id.swipe)
        webContainer = findViewById(R.id.web_container)
        errorPanel = findViewById(R.id.error_panel)
        errorMessage = findViewById(R.id.error_message)
        setupPanel = findViewById(R.id.setup_panel)
        urlInput = findViewById(R.id.setup_url)
        tokenInput = findViewById(R.id.setup_token)
        cancelButton = findViewById(R.id.setup_cancel)

        setUpInsets()
        setUpBack()
        swipe.setColorSchemeColors(getColor(R.color.lh_web_background))
        swipe.setOnChildScrollUpCallback { _, _ -> webView.let { it == null || it.scrollY > 0 || !pullAllowed } }
        swipe.setOnRefreshListener { retry() }
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
                if (savedInstanceState == null && intent?.action == ACTION_SERVER_SETTINGS) showSetup(saved)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SERVER_SETTINGS) showSetup(config)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let { web -> outState.putBundle(STATE_WEB, Bundle().also { web.saveState(it) }) }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    // ---- Server and WebView ------------------------------------------------------------------

    /** Shows [cfg] in a fresh WebView, so listeners and scripts are scoped to its origin only. */
    private fun openServer(cfg: ServerConfig, state: Bundle?) {
        config = cfg
        failedUrl = null
        webView?.let { webContainer.removeView(it); it.destroy() }
        val web = createWebView(cfg)
        webView = web
        downloads = Downloads(this, lifecycleScope, cfg)
        webContainer.addView(web, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        swipe.isVisible = true
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
        // Blob exports: an origin-scoped message channel, not addJavascriptInterface.
        // Without these features blob exports fall through to the DownloadListener's toast.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val rules = setOf(cfg.origin)
                WebViewCompat.addWebMessageListener(web, WebScripts.DOWNLOAD_BRIDGE, rules) { _, message, _, isMainFrame, _ ->
                    if (isMainFrame) message.data?.let { downloads?.saveBlob(it) }
                }
                WebViewCompat.addDocumentStartJavaScript(web, WebScripts.BLOB_DOWNLOADS, rules)
            }
        }
        web.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                pullAllowed = false
                val density = resources.displayMetrics.density
                web.evaluateJavascript(WebScripts.canPullToRefresh(event.x / density, event.y / density)) {
                    pullAllowed = it == "true"
                }
            }
            false
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

    private fun showError(message: String) {
        swipe.isRefreshing = false
        errorMessage.text = message
        errorPanel.isVisible = true
        updateBarAppearance()
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

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (view == webView) failedUrl = null
        }

        override fun onPageCommitVisible(view: WebView, url: String) = injectInsets(view)

        override fun onPageFinished(view: WebView, url: String) {
            swipe.isRefreshing = false
            injectInsets(view)
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view != webView || !request.isForMainFrame) return
            failedUrl = request.url.toString()
            val host = request.url.host ?: request.url.toString()
            showError(
                if (!isOnline()) getString(R.string.error_offline)
                else getString(R.string.error_unreachable, host, error.description),
            )
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (view != webView || !request.isForMainFrame) return
            if (response.statusCode == 401 || response.statusCode == 403) {
                failedUrl = request.url.toString()
                showError(getString(R.string.error_auth, response.statusCode))
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
            val spinnerStart = safe.top
            swipe.setProgressViewOffset(false, spinnerStart, spinnerStart + (64 * resources.displayMetrics.density).toInt())
            webView?.let(::injectInsets)
            insets.inset(0, 0, 0, ime.bottom)
        }
        for (panel in listOf(errorPanel, setupPanel)) {
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = webView
                when {
                    setupPanel.isVisible && config != null -> hideSetup()
                    web != null && web.canGoBack() -> {
                        errorPanel.isVisible = false
                        updateBarAppearance()
                        web.goBack()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val ACTION_SERVER_SETTINGS = "com.jiiro.learnharness.action.SERVER_SETTINGS"
        const val STATE_WEB = "web"
    }
}
