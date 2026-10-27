package com.jiiro.learnharness

import java.net.URI
import java.net.URISyntaxException

/** The server the app shows, as saved on the setup screen. */
data class ServerConfig(val url: String, val token: String?) {
    val origin: String get() = requireNotNull(ServerUrls.origin(url)) { "invalid saved server URL" }
    val isHttps: Boolean get() = url.startsWith("https://")
}

/** URL rules for the shell. Pure JVM so unit tests cover them. */
object ServerUrls {
    const val TOKEN_COOKIE = "lh_token"

    /**
     * Normalizes what the user typed into `scheme://host[:port][/path]` without a trailing slash,
     * query or fragment. A missing scheme means https. Returns null for anything that is not a
     * plain http(s) URL with a host.
     */
    fun normalize(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "https://$text"
        val uri = parse(text) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val path = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://$host${portSuffix(scheme, uri.port)}$path"
    }

    /** `scheme://host[:port]`, with the scheme's default port left out; null if not http(s). */
    fun origin(url: String): String? {
        val uri = parse(url) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return "$scheme://$host${portSuffix(scheme, uri.port)}"
    }

    fun sameOrigin(url: String, origin: String): Boolean = origin(url)?.let { it == origin } ?: false

    /**
     * True unless [url] is plain http to a host outside the tailnet (see
     * res/xml/network_security_config.xml): http is allowed only to *.oxy.internal (Headscale
     * MagicDNS), *.ts.net, 100.64.0.0/10 addresses, and localhost / the emulator's host alias.
     */
    fun cleartextAllowed(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (uri.scheme?.lowercase() != "http") return true
        val host = uri.host?.lowercase() ?: return false
        return host == "oxy.internal" || host.endsWith(".oxy.internal") || host.endsWith(".ts.net") ||
            host == "localhost" || host == "10.0.2.2" || isTailnetIpv4(host)
    }

    /** A dotted-quad IPv4 literal in 100.64.0.0/10 (the CGNAT range Headscale and Tailscale use). */
    fun isTailnetIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return false
            part.toInt().also { if (it > 255) return false }
        }
        return octets[0] == 100 && octets[1] in 64..127
    }

    /** Tokens go into a cookie and a header unquoted, so they must be plain visible ASCII. */
    fun isValidToken(token: String): Boolean =
        token.isNotEmpty() && token.all { it in '!'..'~' && it !in "\";,\\" }

    /** Set-Cookie value for the token. Secure only over https, or the WebView would drop it. */
    fun tokenCookie(token: String, secure: Boolean): String =
        "$TOKEN_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax" + if (secure) "; Secure" else ""

    /** Set-Cookie value that removes a previously stored token. */
    fun clearTokenCookie(secure: Boolean): String =
        "$TOKEN_COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + if (secure) "; Secure" else ""

    private fun parse(text: String): URI? = try {
        URI(text)
    } catch (_: URISyntaxException) {
        null
    }

    private fun portSuffix(scheme: String, port: Int): String = when {
        port == -1 -> ""
        scheme == "https" && port == 443 -> ""
        scheme == "http" && port == 80 -> ""
        else -> ":$port"
    }
}
