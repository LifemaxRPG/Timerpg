import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
// QuickJS-ng source (runs the TypeScript simulation, docs/LOOP.md): quickjs.dir in local.properties, else third_party/quickjs-ng (tools/get-quickjs.sh).
val quickjsDir: File = localProps.getProperty("quickjs.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
    ?: rootProject.file("third_party/quickjs-ng")

// Every build a new version: the number of commits (an update must have a higher versionCode than what is installed).
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)
val commitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("dev")

android {
    // the release build skips lint-vital: it trips over copyWorldAssets' output and checks nothing the game needs
    lint { checkReleaseBuilds = false; abortOnError = false }
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
                arguments += listOf("-DLLAMA_DIR=${llamaDir.absolutePath}", "-DJOLT_DIR=${joltDir.absolutePath}", "-DQUICKJS_DIR=${quickjsDir.absolutePath}", "-DCMAKE_BUILD_TYPE=Release")
                // the Adreno GPU backend, when the OpenCL headers and link stub are there (tools/get-opencl.sh)
                localProps.getProperty("opencl.headers")?.let { arguments += "-DTIMERPG_OPENCL_HEADERS=${rootProject.file(it).absolutePath}" }
                localProps.getProperty("opencl.lib")?.let { arguments += "-DTIMERPG_OPENCL_LIB=${rootProject.file(it).absolutePath}" }
                // and the Hexagon NPU, with the Hexagon SDK (tools/get-hexagon.sh)
                localProps.getProperty("hexagon.sdk")?.let { arguments += "-DTIMERPG_HEXAGON_SDK=${rootProject.file(it).absolutePath}" }
                // Only what the app loads: our JNI library, llama.cpp's shared libraries and three CPU kernel variants.
                targets += listOf(
                    "timerpg_llm", "timerpg_physics", "timerpg_simjs", "llama", "ggml", "ggml-base", "mtmd",
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
        // a build with our ggml-hexagon patches (llama.cpp-patched/) over an older one without (llama.cpp/), newest first
        val skels = fileTree(projectDir.resolve(".cxx")) { include("**/arm64-v8a/**/libggml-htp-v*.so") }.files
        val patched = skels.filter { "/llama.cpp-patched/" in it.invariantSeparatorsPath }
        copy {
            from((patched.ifEmpty { skels }).sortedByDescending { it.lastModified() })
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
        if (!quickjsDir.resolve("quickjs.h").isFile) {
            throw GradleException("QuickJS-ng not found at $quickjsDir. Run kotlin-street/tools/get-quickjs.sh, or set quickjs.dir=/path/to/quickjs-ng in kotlin-street/local.properties.")
        }
    }
}

// Filament 1.81.0 with no handle debug tags (docs/TOWN.md, "The freeze on the Meridian's steps"; the S25's freeze of
// 0.1.805, 050341): its OpenGL driver keeps a debug tag for every GPU handle in a tsl::robin_map keyed by the handle's id,
// whose age bits (27-28) sit above the table's mask, so a pool slot reused over its ages puts several keys in one bucket.
// Once a few thousand of the smallest handles (each material instance's descriptor set and uniform buffer) have been
// reused that way, one insert's probe passes 8192 and the map rehashes, doubling, without end on the driver's thread:
// native memory to gigabytes in seconds, the main thread waiting in Renderer.beginFrame, "not responding". The tags only
// name handles in Filament's error messages. The fix: DebugTag::writePoolHandleTag and writeHeapHandleTag return at once
// (their first instruction a `ret`), in exactly this build of libfilament-jni.so (checked by its SHA-256 and the bytes
// patched); the desktop build has the same change in its source (tools/build-filament.sh). FilamentHandleTagTest
// churns material instances the way the town does and fails without it.
val filamentAarOriginal = configurations.detachedConfiguration(
    dependencies.create("com.google.android.filament:filament-android:1.81.0@aar")).apply { isTransitive = false }
val patchedFilamentAar = layout.buildDirectory.file("filament-notags/filament-android-1.81.0-notags.aar")
val patchFilamentAar = tasks.register("patchFilamentAar") {
    inputs.files(filamentAarOriginal)
    outputs.file(patchedFilamentAar)
    doLast {
        val lib = "jni/arm64-v8a/libfilament-jni.so"
        val sha = "56ab04c59a91b69a3e7a5166daab60a7e444775aa5068931b534d97ef3a68fbb"
        // (file offsets = addresses in its first, executable segment: writePoolHandleTag, writeHeapHandleTag)
        val at = listOf(0x2103d0, 0x210480)
        val prologue = byteArrayOf(0xff.toByte(), 0xc3.toByte(), 0x00, 0xd1.toByte())   // sub sp, sp, #0x30
        val ret = byteArrayOf(0xc0.toByte(), 0x03, 0x5f, 0xd6.toByte())                 // ret
        val out = patchedFilamentAar.get().asFile
        out.parentFile.mkdirs()
        var patched = false
        ZipFile(filamentAarOriginal.singleFile).use { zin ->
            ZipOutputStream(out.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    var bytes = zin.getInputStream(e).use { it.readBytes() }
                    if (e.name == lib) {
                        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        check(got == sha) { "filament-android 1.81.0's $lib is not the build the tag patch was made for ($got)" }
                        bytes = bytes.copyOf()
                        for (a in at) {
                            check(bytes.copyOfRange(a, a + 4).contentEquals(prologue)) { "unexpected code at 0x%x in $lib".format(a) }
                            ret.copyInto(bytes, a)
                        }
                        patched = true
                    }
                    zout.putNextEntry(ZipEntry(e.name).also { it.time = e.time })
                    zout.write(bytes)
                    zout.closeEntry()
                }
            }
        }
        check(patched) { "no $lib in the Filament AAR" }
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
    // (the AAR from Maven with one fix in its arm64 library: no handle debug tags, the freeze on the Meridian's
    // steps; patchFilamentAar below)
    implementation(files(patchedFilamentAar).builtBy(patchFilamentAar))
    // JVM unit tests (the validate() port, the pre-generation queue): gradle :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // the real org.json for tests that parse dialogue.json (android.jar only has stubs that throw on the JVM)
    testImplementation("org.json:json:20240303")
}

// Filament 1.81's AAR asks for compileSdk 37, which this project's Android Gradle plugin (8.7) and SDK (34) do not have.
// Its classes are used through APIs this SDK has (its own code checks the phone's version at run time), so the
// metadata check is skipped for now; moving the project to AGP 9 and compileSdk 37 is the proper fix.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
