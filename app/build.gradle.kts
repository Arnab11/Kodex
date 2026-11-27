plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.invictus.kodex"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.invictus.kodex"
        minSdk = 30
        targetSdk = 36
        // CI passes these on tag builds (v1.2.3 -> name 1.2.3, code derived from the tag
        // by .github/scripts/version-from-tag.sh so pre-releases always sort below the stable).
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1030000 // 1.3.0 stable (see version-from-tag.sh)
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.3.0"
        // Commit this build was made from (CI passes -PgitSha=<full sha>). Empty for local builds.
        // GitHub Manager compares it with the commit of a downloaded APK to show Open vs Update.
        buildConfigField("String", "GIT_SHA", "\"${(project.findProperty("gitSha") as String?).orEmpty().trim()}\"")
        // When this APK was compiled. A non-preview build (PREVIEW_RUN == 0) compiled after a preview run
        // finished is newer than that preview, even if the commit compare is unavailable.
        buildConfigField("long", "BUILD_TIME_MS", "${System.currentTimeMillis()}L")
        // Run number of the preview.yml workflow run that built this APK (0 for every other build).
        // The Preview update channel compares it with the newest successful run of that workflow.
        buildConfigField("int", "PREVIEW_RUN", "0")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 code shrinking + obfuscation. Keep rules live in proguard-rules.pro
            // (JGit, Sora/tm4e, Room, WebView). Resource shrinking stays off for now.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signingConfig here on purpose: this build type produces an
            // unsigned APK (app-release-unsigned.apk). Signing is done
            // explicitly with apksigner (.github/scripts/sign-apks.sh, called from the
            // build / release workflows) or manually for local release testing), keeping build and
            // sign as separate, visible steps.
        }
        // Manual preview builds (preview.yml): a release clone with the same applicationId, so it installs
        // over / under stable builds signed with the same key. Unsigned here for the same reason as `release`.
        create("preview") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-beta.r${previewRun()}"
            buildConfigField("int", "PREVIEW_RUN", previewRun())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // One APK per ABI (arm64-v8a, armeabi-v7a; each contains only that ABI's native libs)
    // + a universal APK. x86 / x86_64 are intentionally not built.
    // Only for release / preview builds so local debug installs stay a single APK.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) || it.contains("Preview", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // Sora/tm4e pull in several jars that each ship these; harmless to drop.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.termux.terminal.emulator)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // M5 preview: WebViewAssetLoader (markdown/HTML), Coil (images), AndroidSVG (svg -> Picture).
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.okhttp) // GitHub profile fetch + avatar cache interceptor (same version Coil 2.7 uses)
    implementation(libs.androidsvg)

    // M6 git: JGit (pure-Java Git) + silent SLF4J provider (JGit's log chatter is
    // dropped; real errors surface through GitErrorDetails dialogs).
    implementation(libs.org.eclipse.jgit)
    implementation(libs.slf4j.nop)
    // Archive explorer: 7z (+ LZMA via xz) and RAR; zip/jar/aar/apk use java.util.zip.
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(libs.junrar)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.compose.material.symbols)
    implementation(libs.compose.material.symbols.rounded.filled)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests for pure logic (M15). org.json: android.jar only ships stubs on the JVM.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

// Local helper for the Image tracer's native core: ./gradlew buildNative
// (needs Rust + cargo-ndk + an Android NDK; CI runs the same script before Gradle).
// Without it the tool still opens and just reports that the native library is missing.
tasks.register<Exec>("buildNative") {
    group = "build"
    description = "Builds libkodex_vtracer.so for arm64-v8a and armeabi-v7a into src/main/jniLibs"
    commandLine("bash", rootProject.file(".github/scripts/build-native.sh").absolutePath)
}

// GITHUB_RUN_NUMBER is set by preview.yml (and any other CI run); locally it is missing, so 0 -- a local
// preview build then always offers the latest preview. Must stay a plain integer: it is emitted as
// `int PREVIEW_RUN` into BuildConfig.
fun previewRun(): String =
    System.getenv("GITHUB_RUN_NUMBER")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: "0"
