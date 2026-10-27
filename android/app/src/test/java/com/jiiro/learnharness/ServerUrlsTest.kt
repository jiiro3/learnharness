package com.jiiro.learnharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlsTest {
    @Test fun normalizeAddsHttpsAndDropsTrailingSlashQueryAndFragment() {
        assertEquals("https://desk.tail1234.ts.net", ServerUrls.normalize("  desk.tail1234.ts.net/ "))
        assertEquals("https://desk.tail1234.ts.net", ServerUrls.normalize("HTTPS://Desk.Tail1234.ts.net/?a=1#x"))
        assertEquals("http://desk.tail1234.ts.net:3000/lh", ServerUrls.normalize("http://desk.tail1234.ts.net:3000/lh/"))
    }

    @Test fun normalizeDropsDefaultPorts() {
        assertEquals("https://example.com", ServerUrls.normalize("https://example.com:443"))
        assertEquals("http://localhost", ServerUrls.normalize("http://localhost:80/"))
    }

    @Test fun normalizeRejectsNonWebAndMalformedUrls() {
        assertNull(ServerUrls.normalize(""))
        assertNull(ServerUrls.normalize("ftp://example.com"))
        assertNull(ServerUrls.normalize("javascript:alert(1)"))
        assertNull(ServerUrls.normalize("https://user:pw@example.com"))
        assertNull(ServerUrls.normalize("https://exa mple.com"))
    }

    @Test fun sameOriginComparesSchemeHostAndPort() {
        val origin = "https://desk.tail1234.ts.net"
        assertTrue(ServerUrls.sameOrigin("https://desk.tail1234.ts.net/chat/1?x", origin))
        assertTrue(ServerUrls.sameOrigin("https://DESK.tail1234.ts.net:443/", origin))
        assertFalse(ServerUrls.sameOrigin("http://desk.tail1234.ts.net/", origin))
        assertFalse(ServerUrls.sameOrigin("https://desk.tail1234.ts.net:8443/", origin))
        assertFalse(ServerUrls.sameOrigin("https://desk.tail1234.ts.net.evil.com/", origin))
        assertFalse(ServerUrls.sameOrigin("https://github.com/jiiro3/learnharness", origin))
        assertFalse(ServerUrls.sameOrigin("not a url", origin))
    }

    @Test fun configOriginIgnoresPath() {
        assertEquals("http://desk.tail1234.ts.net:3000", ServerConfig("http://desk.tail1234.ts.net:3000/lh", null).origin)
    }

    @Test fun cleartextOnlyForTailnetAndLocalhost() {
        assertTrue(ServerUrls.cleartextAllowed("https://example.com"))
        assertTrue(ServerUrls.cleartextAllowed("http://desk.tail1234.ts.net:3000"))
        assertTrue(ServerUrls.cleartextAllowed("http://localhost:3000"))
        assertFalse(ServerUrls.cleartextAllowed("http://example.com"))
        assertFalse(ServerUrls.cleartextAllowed("http://192.168.1.5:3000"))
        assertFalse(ServerUrls.cleartextAllowed("http://evilts.net"))
    }

    @Test fun cleartextForHeadscaleTailnet() {
        assertTrue(ServerUrls.cleartextAllowed("http://learnharness.oxy.internal:3000"))
        assertTrue(ServerUrls.cleartextAllowed("http://oxy.internal"))
        assertTrue(ServerUrls.cleartextAllowed("http://100.64.0.1:3000"))
        assertTrue(ServerUrls.cleartextAllowed("http://100.127.255.254"))
        assertFalse(ServerUrls.cleartextAllowed("http://100.63.255.255"))
        assertFalse(ServerUrls.cleartextAllowed("http://100.128.0.1"))
        assertFalse(ServerUrls.cleartextAllowed("http://100.64.0.256"))
        assertFalse(ServerUrls.cleartextAllowed("http://100.64.1"))
        assertFalse(ServerUrls.cleartextAllowed("http://100.64.0.1.example.com"))
        assertFalse(ServerUrls.cleartextAllowed("http://evil-oxy.internal"))
        assertFalse(ServerUrls.cleartextAllowed("http://oxy.internal.example.com"))
    }

    @Test fun httpOriginCookieIsNotSecure() {
        val config = ServerConfig(requireNotNull(ServerUrls.normalize("http://learnharness.oxy.internal:3000")), "t0k")
        assertEquals("http://learnharness.oxy.internal:3000", config.origin)
        assertFalse(config.isHttps)
        assertEquals("lh_token=t0k; Path=/; HttpOnly; SameSite=Lax", ServerUrls.tokenCookie("t0k", config.isHttps))
    }

    @Test fun tokenCookie() {
        assertEquals("lh_token=abc; Path=/; HttpOnly; SameSite=Lax; Secure", ServerUrls.tokenCookie("abc", secure = true))
        assertEquals("lh_token=abc; Path=/; HttpOnly; SameSite=Lax", ServerUrls.tokenCookie("abc", secure = false))
        assertTrue(ServerUrls.clearTokenCookie(secure = true).startsWith("lh_token=; Path=/; Max-Age=0"))
    }

    @Test fun tokenValidation() {
        assertTrue(ServerUrls.isValidToken("lh_9f8A-._~+/="))
        assertFalse(ServerUrls.isValidToken(""))
        assertFalse(ServerUrls.isValidToken("has space"))
        assertFalse(ServerUrls.isValidToken("semi;colon"))
        assertFalse(ServerUrls.isValidToken("quote\""))
        assertFalse(ServerUrls.isValidToken("ünicode"))
    }
}
