package com.jiiro.learnharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishedApkTest {
    private val sha = "17a0a3733a9eef6d6550c709727dec4b68bb06bbabf14b084d71dc6046e71941"

    @Test fun parsesTheLatestEndpoint() {
        val apk = PublishedApk.parse("""{"versionCode": 3, "versionName": "0.1.2", "sha256": "${sha.uppercase()}", "size": 7785308}""")
        assertEquals(PublishedApk(3, "0.1.2", sha, 7_785_308), apk)
    }

    @Test fun rejectsMissingOrMalformedFields() {
        assertNull(PublishedApk.parse("not json"))
        assertNull(PublishedApk.parse("""{"versionName": "0.1.2", "sha256": "$sha", "size": 1}"""))
        assertNull(PublishedApk.parse("""{"versionCode": 3, "versionName": "0.1.2", "sha256": "abc", "size": 1}"""))
        assertNull(PublishedApk.parse("""{"versionCode": 3, "versionName": "0.1.2", "sha256": "$sha", "size": 0}"""))
        assertNull(PublishedApk.parse("""{"versionCode": 0, "versionName": "0.1.2", "sha256": "$sha", "size": 1}"""))
        assertNull(PublishedApk.parse("""{"versionCode": 3, "versionName": " ", "sha256": "$sha", "size": 1}"""))
    }
}
