import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// llama.cpp source for the native language model: llama.dir in local.properties (relative to kotlin-street/), else
// third_party/llama.cpp (tools/get-llama.sh). If neither exists, the native build stops and says to run get-llama.sh.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val llamaDir: File = localProps.getProperty("llama.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
    ?: rootProject.file("third_party/llama.cpp")
// Jolt Physics source (the cars, docs/VEHICLES.md): jolt.dir in local.properties, else third_party/JoltPhysics (tools/get-jolt.sh).
val joltDir: File = localProps.getProperty("jolt.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
    ?: rootProject.file("third_party/JoltPhysics")

// Every build a new version: the number of commits (an update must have a higher versionCode than what is installed).
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)
val commitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("dev")

android {
    namespace = "com.timerpg.street"
    compileSdk = 34
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.timerpg.street"
        minSdk = 24
        targetSdk = 34
        versionCode = commitCount
        versionName = "0.1.$commitCount ($commitSha)"
        // the commit, for screenshots and crash reports
        buildConfigField("String", "GIT_SHA", "\"$commitSha\"")
        // the owner's build (every build made from this repository so far): the playtest recorder keeps their NPC
        // conversations by default ("Record my conversations", docs/PLAYTEST.md); owner.build=false in local.properties for others
        buildConfigField("boolean", "OWNER_BUILD", (localProps.getProperty("owner.build")?.trim() ?: "true").toBoolean().toString())
        // where the app looks for a newer build (tools/publish-apk.sh puts it there)
        buildConfigField("String", "UPDATE_URL", "\"https://raw.githubusercontent.com/lifemaxrpg/timerpg/apk/update.json\"")
        // The model runs on 64-bit ARM phones only.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // Release flags even in debug APKs: an unoptimised model is unusably slow.
                arguments += listOf("-DLLAMA_DIR=${llamaDir.absolutePath}", "-DJOLT_DIR=${joltDir.absolutePath}", "-DCMAKE_BUILD_TYPE=Release")
                // the Adreno GPU backend, when the OpenCL headers and link stub are there (tools/get-opencl.sh)
                localProps.getProperty("opencl.headers")?.let { arguments += "-DTIMERPG_OPENCL_HEADERS=${rootProject.file(it).absolutePath}" }
                localProps.getProperty("opencl.lib")?.let { arguments += "-DTIMERPG_OPENCL_LIB=${rootProject.file(it).absolutePath}" }
                // and the Hexagon NPU, with the Hexagon SDK (tools/get-hexagon.sh)
                localProps.getProperty("hexagon.sdk")?.let { arguments += "-DTIMERPG_HEXAGON_SDK=${rootProject.file(it).absolutePath}" }
                // Only what the app loads: our JNI library, llama.cpp's shared libraries and three CPU kernel variants.
                targets += listOf(
                    "timerpg_llm", "timerpg_physics", "llama", "ggml", "ggml-base", "mtmd",
                    "ggml-cpu-android_armv8.6_1", "ggml-cpu-android_armv8.2_2", "ggml-cpu-android_armv8.0_1",
                )
                if (localProps.getProperty("opencl.lib") != null) targets += "ggml-opencl"
                if (localProps.getProperty("hexagon.sdk") != null) targets += "ggml-hexagon"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    // One key for every build, from any machine: app/signing/timerpg-dev.keystore (the key the first builds were signed
    // with), so each new APK installs over the last as an update instead of being refused as a different app.
    signingConfigs {
        create("timerpg") {
            storeFile = file("signing/timerpg-dev.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("timerpg") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("timerpg") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
    // the Filament renderer trial (shared with the desktop's headless renders)
    sourceSets["main"].java.srcDir("../shared/src/filament/kotlin")
    // the NPU's own code (libggml-htp-vNN.so, built for the Hexagon DSP by the native build) goes into the APK beside the rest
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("htp"))
    // the baked world skeleton (docs/WORLD_GEN.md W4: content/world/<seed>/skeleton.twsk.gz, kotlin-street/tools/bake-world.ts)
    // as assets/world/<seed>/, copied in at each build from the repository's content folder
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("worldAssets"))
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // the OpenCL link stub is not shipped: the phone's own driver (libOpenCL.so, a public vendor library) is used
        jniLibs.excludes += "**/libOpenCL.so"
        // Native libraries compressed in the APK and extracted at install: the APK grows ~3.5 MB instead of ~8.6 MB.
        // (llm_core loads its CPU kernel library by name, which works either way.)
        jniLibs.useLegacyPackaging = true
    }
}

// The DSP libraries the Hexagon build makes are not CMake targets Gradle packages itself: copied after each native build.
tasks.matching { it.name.startsWith("buildCMake") }.configureEach {
    doLast {
        copy {
            from(fileTree(projectDir.resolve(".cxx")) { include("**/arm64-v8a/**/libggml-htp-v*.so") })
            into(layout.buildDirectory.dir("htp/arm64-v8a"))
            eachFile { path = name }
            includeEmptyDirs = false
            // each one is there twice (the build's and its install copy): one is enough
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}

val copyWorldAssets = tasks.register<Copy>("copyWorldAssets") {
    from(rootProject.file("../content/world")) { include("*/skeleton.twsk.gz") }
    into(layout.buildDirectory.dir("worldAssets/world"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") || it.name.startsWith("generate") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyWorldAssets) }

// A clear message instead of a CMake error deep in the log when llama.cpp is missing.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    doFirst {
        if (!llamaDir.resolve("include/llama.h").isFile) {
            throw GradleException("llama.cpp not found at $llamaDir. Run kotlin-street/tools/get-llama.sh, or set llama.dir=/path/to/llama.cpp in kotlin-street/local.properties.")
        }
        if (!joltDir.resolve("Jolt/Jolt.h").isFile) {
            throw GradleException("Jolt Physics not found at $joltDir. Run kotlin-street/tools/get-jolt.sh, or set jolt.dir=/path/to/JoltPhysics in kotlin-street/local.properties.")
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")   // NPC voices: Kokoro and Supertonic
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // the GitHub token for "Screenshots to Claude", kept encrypted (EncryptedSharedPreferences, a Keystore key)
    implementation("androidx.security:security-crypto:1.1.0")
    // the renderer trial: Google's Filament (OpenGL ES and Vulkan), the same version as the desktop build and matc
    // (without its own dependencies: they would raise the Kotlin standard library past this project's compiler; it needs
    // only androidx.annotation, which the app already has)
    implementation("com.google.android.filament:filament-android:1.81.0") { isTransitive = false }
    // JVM unit tests (the validate() port, the pre-generation queue): gradle :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // the real org.json for tests that parse dialogue.json (android.jar only has stubs that throw on the JVM)
    testImplementation("org.json:json:20240303")
}

// Filament 1.81's AAR asks for compileSdk 37, which this project's Android Gradle plugin (8.7) and SDK (34) do not have.
// Its classes are used through APIs this SDK has (its own code checks the phone's version at run time), so the
// metadata check is skipped for now; moving the project to AGP 9 and compileSdk 37 is the proper fix.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
