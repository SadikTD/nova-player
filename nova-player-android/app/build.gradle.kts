import java.io.File
import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sadik.novaplayer"
    // Same applicationId as the desktop's electron-builder appId.

    compileSdk = 36
    ndkVersion = "27.0.12077973"
    // Play requires targetSdk 36 (Android 16) for new apps as of 2026-08-31.
    defaultConfig {
        applicationId = "com.sadik.novaplayer"
        targetSdk = 36
        minSdk = 23
        versionCode = 6
        versionName = "1.2.1"
        manifestPlaceholders["appLabel"] = "Nova Player"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "com.sadik.novaplayer.NativeAnalysisTest"

        externalNativeBuild {
            cmake {
                cppFlags += ""
                // The bridge exposes only C/JNI APIs. Keep its runtime private;
                // libmpv ships the shared runtime from its own NDK toolchain.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    // Release signing key lives OUTSIDE the repo (default: %USERPROFILE%/NovaPlayer-signing).
    // Every update must be signed with this same key — back that folder up.
    val signingProps = File(System.getenv("NOVA_SIGNING_PROPERTIES") ?: (System.getProperty("user.home") + "/NovaPlayer-signing/keystore.properties"))
    signingConfigs {
        if (signingProps.isFile) create("release") {
            val p = Properties().apply { signingProps.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (signingProps.isFile) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Nova Player"
        }
        // Release speed (R8, no debuggable penalty on Compose) but the debug app id + key, so it
        // installs over the dev build on the phone without losing history, settings or playlists.
        create("fast") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-fast"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            manifestPlaceholders["appLabel"] = "Nova Player"
        }
    }

    // github = the APK on GitHub Releases, which keeps itself up to date (Updater.kt) like the
    // desktop app. play = the Play Store bundle: Play forbids self-updates and the install
    // permission, so it has neither.
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store"; buildConfigField("boolean", "SELF_UPDATE", "true") }
        create("play") { dimension = "store"; buildConfigField("boolean", "SELF_UPDATE", "false") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Compressed native libraries: the APK download is about half the size
            // (32 MB instead of ~65 MB); Android extracts them once at install.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// ---------------------------------------------------------------------------
// Native-library integrity check.
//
// scripts/build-native.sh and scripts/build-alass.sh write native-libs.sha256
// covering every .so under src/main/jniLibs. Verify it before any packaging so a
// silently-changed binary (a re-run of CI, a compromised artifact) can never
// reach users. This is also the hook that makes the GPL "corresponding source"
// offer honest: the workflow + pins rebuild exactly the bytes we ship.
// ---------------------------------------------------------------------------
val verifyNativeLibs = tasks.register("verifyNativeLibs") {
    group = "verification"
    description = "Verify src/main/jniLibs against native-libs.sha256"
    doLast {
        val jniDir = file("src/main/jniLibs")
        val manifest = File(jniDir, "native-libs.sha256")
        if (!manifest.exists()) {
            throw GradleException(
                "native-libs.sha256 is missing. Run scripts/build-native.sh and " +
                    "scripts/build-alass.sh (or download the native-libs release) first."
            )
        }
        val expect = mutableMapOf<String, String>()
        manifest.readLines().forEach { line ->
            val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").find(line.trim())
            if (m != null) {
                // Manifest paths are relative to jniLibs, e.g. "./arm64-v8a/libmpv.so"
                expect[m.groupValues[2].removePrefix("./")] = m.groupValues[1].lowercase()
            }
        }
        if (expect.isEmpty()) throw GradleException("native-libs.sha256 parsed to zero entries")

        var failures = 0
        expect.forEach { (rel, want) ->
            val f = File(jniDir, rel)
            if (!f.exists()) {
                logger.error("MISSING  $rel")
                failures++
                return@forEach
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(f.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (digest != want) {
                logger.error("MISMATCH $rel\n  expected $want\n  actual   $digest")
                failures++
            }
        }
        // Report any .so on disk that the manifest does not know about — those
        // are exactly the "sneaked in binary" case this check exists for.
        jniDir.walkTopDown()
            .filter { it.isFile && it.extension == "so" }
            .forEach { f ->
                val rel = f.relativeTo(jniDir).path.replace('\\', '/')
                if (rel !in expect) {
                    logger.error("UNLISTED $rel (present on disk but not in native-libs.sha256)")
                    failures++
                }
            }

        if (failures > 0) {
            throw GradleException("$failures native library integrity failure(s) — refusing to package")
        }
        logger.lifecycle("native-libs: ${expect.size} libraries verified")
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(verifyNativeLibs) }
// Resource-only packaging also runs for JVM tests and needs no native binaries.
tasks.matching { Regex("(package|bundle)\\w*(Debug|Release|Fast)").matches(it.name) }
    .configureEach { dependsOn(verifyNativeLibs) }

val verifyReleaseNativeSources = tasks.register("verifyReleaseNativeSources") {
    doLast {
        if (file("src/main/jniLibs/debug-origin.json").exists()) {
            throw GradleException("These native libraries are for a local playback prototype. Build and validate the complete native payload before making a release.")
        }
    }
}
tasks.matching { Regex("pre\\w*ReleaseBuild").matches(it.name) }.configureEach { dependsOn(verifyReleaseNativeSources) }

dependencies {
    // Keep Compose compatible with this project's SDK 36 / AGP 8.13 toolchain.
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    testImplementation("junit:junit:4.13.2")
}



