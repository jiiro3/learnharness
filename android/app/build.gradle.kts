import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Build switches (scripts/build.sh passes extra -P arguments through).
// The app stores an access token, so ADB gets no run-as or debugger access by default.
// -PlhDeveloperDebug=true makes the debug build debuggable and turns on WebView remote
// debugging (chrome://inspect) for web layout work.
val developerDebug = providers.gradleProperty("lhDeveloperDebug").orNull == "true"

// Every build signs with the SDK debug key in ~/.android/debug.keystore, the key 0.1.0 shipped
// with. In-app updates only install over an APK with the same signature, so a different key
// would strand installed phones: verifySigningKey fails the build unless the key's certificate
// SHA-256 equals lhSigningCertSha256 in gradle.properties.
val signingKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
val pinnedSigningCert = providers.gradleProperty("lhSigningCertSha256").get().lowercase().replace(":", "")
val verifySigningKey by tasks.registering {
    description = "Fails unless the signing key is the one LearnHarness 0.1.0 shipped with."
    doLast {
        if (!signingKeystore.isFile) {
            throw GradleException("Signing keystore $signingKeystore is missing. Restore it from backup; a new key can't update installed apps.")
        }
        val keyStore = KeyStore.getInstance(signingKeystore, "android".toCharArray())
        val cert = keyStore.getCertificate("androiddebugkey")
            ?: throw GradleException("$signingKeystore has no 'androiddebugkey' entry.")
        val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        if (actual != pinnedSigningCert) {
            throw GradleException(
                """
                |
                |==================================================================================
                | SIGNING KEY CHANGED. $signingKeystore has certificate SHA-256
                |   $actual
                | but LearnHarness ships signed with
                |   $pinnedSigningCert
                | An APK signed with this key won't install over the app on the phone. Restore the
                | original keystore instead of changing lhSigningCertSha256.
                |==================================================================================
                """.trimMargin(),
            )
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifySigningKey) }

android {
    namespace = "com.jiiro.learnharness"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jiiro.learnharness"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("lhVersionCode").orNull?.toInt() ?: 2
        versionName = "0.1.1"
        buildConfigField("boolean", "WEB_DEBUGGING", developerDebug.toString())
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        // Version-bump nags only: pins are deliberate (gradle/libs.versions.toml).
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    signingConfigs.getByName("debug") {
        storeFile = signingKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    val signing = signingConfigs.getByName("debug")

    buildTypes {
        getByName("debug") {
            isDebuggable = developerDebug
            signingConfig = signing
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
