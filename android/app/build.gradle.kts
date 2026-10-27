import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Build switches (scripts/build.sh passes extra -P arguments through).
// The app stores an access token, so ADB gets no run-as or debugger access by default.
// -PlhDeveloperDebug=true makes the debug build debuggable and turns on WebView remote
// debugging (chrome://inspect) for web layout work.
val developerDebug = providers.gradleProperty("lhDeveloperDebug").orNull == "true"

// Optional owner signing key, kept outside Git, so every build installs over the previous one
// on any machine. Without it the SDK debug key signs, which only matches builds from this host.
val signingPropertiesFile = providers.gradleProperty("lhSigningProperties").map(::File)
    .getOrElse(File(System.getProperty("user.home"), ".config/learnharness/signing/keystore.properties"))
val ownerSigning: Properties? = signingPropertiesFile.takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}
if (ownerSigning == null) {
    logger.lifecycle("No $signingPropertiesFile: signing with the SDK debug key.")
}

android {
    namespace = "com.jiiro.learnharness"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jiiro.learnharness"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("lhVersionCode").orNull?.toInt() ?: 1
        versionName = "0.1.0"
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

    signingConfigs {
        if (ownerSigning != null) {
            create("owner") {
                fun required(key: String): String = ownerSigning.getProperty(key)
                    ?: throw GradleException("$signingPropertiesFile is missing '$key'")
                val store = File(required("storeFile"))
                storeFile = if (store.isAbsolute) store else signingPropertiesFile.resolveSibling(store.path)
                storePassword = required("storePassword")
                keyAlias = required("keyAlias")
                keyPassword = required("keyPassword")
            }
        }
    }
    val signing = signingConfigs.findByName("owner") ?: signingConfigs.getByName("debug")

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
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
}
