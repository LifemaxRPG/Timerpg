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

android {
    namespace = "com.timerpg.street"
    compileSdk = 34
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.timerpg.street"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // The model runs on 64-bit ARM phones only.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // Release flags even in debug APKs: an unoptimised model is unusably slow.
                arguments += listOf("-DLLAMA_DIR=${llamaDir.absolutePath}", "-DCMAKE_BUILD_TYPE=Release")
                // Only what the app loads: our JNI library, llama.cpp's shared libraries and three CPU kernel variants.
                targets += listOf(
                    "timerpg_llm", "llama", "ggml", "ggml-base",
                    "ggml-cpu-android_armv8.6_1", "ggml-cpu-android_armv8.2_2", "ggml-cpu-android_armv8.0_1",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Native libraries compressed in the APK and extracted at install: the APK grows ~3.5 MB instead of ~8.6 MB.
        // (llm_core loads its CPU kernel library by name, which works either way.)
        jniLibs.useLegacyPackaging = true
    }
}

// A clear message instead of a CMake error deep in the log when llama.cpp is missing.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    doFirst {
        if (!llamaDir.resolve("include/llama.h").isFile) {
            throw GradleException("llama.cpp not found at $llamaDir. Run kotlin-street/tools/get-llama.sh, or set llama.dir=/path/to/llama.cpp in kotlin-street/local.properties.")
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
    // JVM unit tests (the validate() port, the pre-generation queue): gradle :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // the real org.json for tests that parse dialogue.json (android.jar only has stubs that throw on the JVM)
    testImplementation("org.json:json:20240303")
}
