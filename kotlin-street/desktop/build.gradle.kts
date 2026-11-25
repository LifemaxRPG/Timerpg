import java.util.Properties as JProperties
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")   // NPC voices (the same API as on Android)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.runtime)
    implementation(compose.animation)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
sourceSets { main { kotlin.srcDir("../shared/src/main/kotlin") } }
// The Filament renderer trial: compiled in only when Filament's headless desktop build is there (tools/build-filament.sh;
// -Pfilament.dist=DIR to use another), so the desktop builds without it as before.
val filamentDist = file((findProperty("filament.dist") as String?) ?: "/home/user/filament-dist")
val withFilament = File(filamentDist, "lib/filament-java.jar").exists()
if (withFilament) {
    sourceSets { main { kotlin.srcDir("../shared/src/filament/kotlin"); kotlin.srcDir("src/filament/kotlin") } }
    dependencies { implementation(files(File(filamentDist, "lib/filament-java.jar"), File(filamentDist, "lib/androidx-annotation.jar"))) }
}
// The physics (docs/VEHICLES.md): Jolt and our JNI wrapper built for this computer (Linux x86-64) from the app's
// app/src/main/cpp/physics, out of tree in build/physics; the preview and the tests load it by -Dtimerpg.physics.lib.
val joltDirDesktop: File = JProperties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
    .getProperty("jolt.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) } ?: rootProject.file("third_party/JoltPhysics")
val physicsDir = layout.buildDirectory.dir("physics").get().asFile
val physicsLib = File(physicsDir, "libtimerpg_physics.so")
val physicsConfigure by tasks.registering(Exec::class) {
    group = "build"
    description = "Configures the physics' native build for this computer"
    val src = file("../app/src/main/cpp/physics")
    inputs.file(File(src, "CMakeLists.txt"))
    outputs.file(File(physicsDir, "build.ninja"))
    commandLine("cmake", "-S", src.absolutePath, "-B", physicsDir.absolutePath, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DJOLT_DIR=${joltDirDesktop.absolutePath}")
    doFirst { if (!joltDirDesktop.resolve("Jolt/Jolt.h").isFile) throw GradleException("Jolt Physics not found at $joltDirDesktop. Run kotlin-street/tools/get-jolt.sh, or set jolt.dir in local.properties.") }
}
val physicsHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the physics (Jolt and its JNI wrapper) for this computer"
    dependsOn(physicsConfigure)
    inputs.dir(file("../app/src/main/cpp/physics"))
    outputs.file(physicsLib)
    commandLine("ninja", "-C", physicsDir.absolutePath, "timerpg_physics", "physics_host_test")
}

// The simulation's JavaScript engine (docs/LOOP.md, "The JavaScript bridge"): QuickJS-ng and our JNI wrapper built for
// this computer from the app's app/src/main/cpp/simjs, out of tree in build/simjs; the tests load it by
// -Dtimerpg.simjs.lib, as the physics.
val quickjsDirDesktop: File = JProperties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
    .getProperty("quickjs.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) } ?: rootProject.file("third_party/quickjs-ng")
val simjsDir = layout.buildDirectory.dir("simjs").get().asFile
val simjsLib = File(simjsDir, "libtimerpg_simjs.so")
val simjsConfigure by tasks.registering(Exec::class) {
    group = "build"
    description = "Configures the JavaScript engine's native build for this computer"
    val src = file("../app/src/main/cpp/simjs")
    inputs.file(File(src, "CMakeLists.txt"))
    outputs.file(File(simjsDir, "build.ninja"))
    commandLine("cmake", "-S", src.absolutePath, "-B", simjsDir.absolutePath, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DQUICKJS_DIR=${quickjsDirDesktop.absolutePath}")
    doFirst { if (!quickjsDirDesktop.resolve("quickjs.h").isFile) throw GradleException("QuickJS-ng not found at $quickjsDirDesktop. Run kotlin-street/tools/get-quickjs.sh, or set quickjs.dir in local.properties.") }
}
val simjsHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the JavaScript engine (QuickJS-ng and its JNI wrapper) for this computer"
    dependsOn(simjsConfigure)
    inputs.dir(file("../app/src/main/cpp/simjs"))
    outputs.file(simjsLib)
    commandLine("ninja", "-C", simjsDir.absolutePath, "timerpg_simjs")
}

// -DtestOnly=HouseGeometryTest,DuskTest: compile and run only those test files (a few of the habit tracker's old tests,
// HabitsViewModelTest and its kin, no longer compile against this engine)
System.getProperty("testOnly")?.let { only ->
    val keep = only.split(",").map { it.trim() }.toSet()
    sourceSets { test { kotlin { exclude { it.file.isFile && it.file.extension == "kt" && it.file.nameWithoutExtension !in keep } } } }
}

// The crossroads' own tests (the camera, the ground plan, the walk round the corner), in a set of their own: the habit
// tracker's suite under src/test needs its data layer, which this repo does not have, so it does not compile here.
// gradle :desktop:crossroadsTest
val crossroadsTest by sourceSets.creating {
    kotlin.srcDir("src/crossroadsTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
dependencies { "crossroadsTestImplementation"("junit:junit:4.13.2") }
// (the Filament trial's own tests, with it)
if (withFilament) crossroadsTest.kotlin.srcDir("src/filamentTest/kotlin")
tasks.register<Test>("crossroadsTest") {
    group = "verification"
    description = "Tests the crossroads: its camera, its ground plan and the walk round the corner"
    testClassesDirs = crossroadsTest.output.classesDirs
    classpath = crossroadsTest.runtimeClasspath
    useJUnit()
    jvmArgs("-Djava.awt.headless=true")
    // (the cars' physics: VehiclePhysicsTest drives them)
    dependsOn(physicsHost)
    jvmArgs("-Dtimerpg.physics.lib=" + physicsLib.absolutePath)
    // (the simulation's JavaScript: SimJsTest runs the bundle in QuickJS and, for comparison, in Node)
    dependsOn(simjsHost)
    jvmArgs("-Dtimerpg.simjs.lib=" + simjsLib.absolutePath,
        "-Dtimerpg.simjs.bundle=" + file("../app/src/main/assets/sim/sim.js").absolutePath,
        "-Dtimerpg.simjs.smoke=" + rootProject.file("tools/sim-smoke.mjs").absolutePath)
    // (the planned town's yards, baked all at once by YardKitTest: about 2.5 times the old town's)
    maxHeapSize = "3g"
    // (GpuRouteTest's comparison run: ROUTE_STREAM=0 holds the whole town; an input, so the switch reruns the test)
    inputs.property("routeStream", System.getenv("ROUTE_STREAM") ?: "")
    if (withFilament) {
        jvmArgs("-Djava.library.path=" + File(filamentDist, "lib").absolutePath)
        environment("EGL_PLATFORM", "surfaceless")
    }
}
kotlin {
    jvmToolchain(21)
    target.compilations.getByName("crossroadsTest").associateWith(target.compilations.getByName("main"))
}
tasks.test { useJUnit() }

tasks.register<JavaExec>("preview") {
    group = "verification"
    description = "Renders the street headlessly to PNG files (no phone needed)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(when (System.getProperty("mode")) { "ocean" -> "FilamentOceanPreviewKt"; "sky" -> "FilamentSkyPreviewKt"; else -> "PreviewKt" })
    jvmArgs("-Djava.awt.headless=true", "-Xmx" + (System.getProperty("xmx") ?: "3g"))
    // (the cars' physics, for the open world's and the cars' previews)
    dependsOn(physicsHost)
    jvmArgs("-Dtimerpg.physics.lib=" + physicsLib.absolutePath)
    // the cars' preview (-Dmode=filament-cars): which year, which shots
    for (k in listOf("carYear", "cars")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // (more JVM flags for a measurement, space separated: -Djvm="-Xlog:gc -XX:ActiveProcessorCount=8")
    System.getProperty("jvm")?.let { jvmArgs(it.split(' ').filter(String::isNotBlank)) }
    System.getProperty("seamdebug")?.let { jvmArgs("-Dseamdebug=$it") }
    System.getProperty("skip")?.let { jvmArgs("-Dskip=$it", "-Donlyone=1") }
    System.getProperty("mode")?.let { jvmArgs("-Dmode=$it") }
    System.getProperty("street")?.let { jvmArgs("-Dstreet=$it") }
    System.getProperty("wx")?.let { jvmArgs("-Dwx=$it") }
    System.getProperty("season")?.let { jvmArgs("-Dseason=$it") }
    System.getProperty("hour")?.let { jvmArgs("-Dhour=$it") }
    System.getProperty("x")?.let { jvmArgs("-Dx=$it") }
    System.getProperty("junctionNoClip")?.let { jvmArgs("-DjunctionNoClip=$it") }
    System.getProperty("rooms")?.let { jvmArgs("-Drooms=$it") }
    System.getProperty("noLit")?.let { jvmArgs("-DnoLit=$it") }
    System.getProperty("assets")?.let { jvmArgs("-Dassets=$it") }
    System.getProperty("day")?.let { jvmArgs("-Dday=$it") }
    System.getProperty("tall")?.let { jvmArgs("-Dtall=$it") }
    // the crossroads and the seam test: where along the mouth, which screen, which way round, the whole sequence, a name
    for (k in listOf("at", "size", "dir", "seq", "tag", "shots", "spot", "houses", "shops"))
        System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    for (k in listOf("turn", "rest", "sky", "look", "eye", "back", "w", "yaw")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the street props' preview: which half (lineup, street) and which rows of the town
    for (k in listOf("props", "rows")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the yards' preview: which half (lineup, real)
    for (k in listOf("yards", "yardLots")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the movie theater's preview (-Dmode=filament-cinema): which eras, or posters
    for (k in listOf("cinema", "cinemaBills")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the stores' preview (-Dmode=filament-stores): which variants or kinds
    System.getProperty("stores")?.let { jvmArgs("-Dstores=$it") }
    System.getProperty("storesFrom")?.let { jvmArgs("-DstoresFrom=${File(it).absolutePath}") }
    System.getProperty("storesYears")?.let { jvmArgs("-DstoresYears=$it") }
    System.getProperty("weekday")?.let { jvmArgs("-Dweekday=$it") }
    System.getProperty("ref")?.let { jvmArgs("-Dref=$it") }
    // the landmark models' preview (-Dmode=filament-landmarks): which models, which year
    for (k in listOf("landmarks", "year", "movingDemo", "movingOut")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the whole town's preview (-Dmode=filament-town): which town folder, where the pack's pictures are, the yards'
    // builder (kit, or placeholder: flat colours)
    for (k in listOf("town", "assetsRoot", "townYards", "memtrace", "heapBudget", "memlive", "coldOnly", "coldLandmarks")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the open world's preview (-Dmode=filament-open): -DtownLod=0 bakes no far yards (to compare)
    for (k in listOf("at", "levelProbe", "seqCount", "seqNoShadow", "treeLevel", "seqScales", "seqViews", "world", "worldFile", "worldYear", "worldNear", "townLod", "townFar", "folk", "see", "farmTag", "farms", "debugeye", "bench", "benchSecs", "gpucost", "rail", "texStats", "astc", "astcExact", "astcCache", "maskFormats", "maskPages", "quant", "glbAs", "glbTag", "glbFile", "gpuOnly", "sky3d", "skyWeather", "skyDay", "stream", "streamDebug", "streamOff", "farLights", "horizon", "worldVis", "impostors", "slod")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    if (withFilament) {
        jvmArgs("-Djava.library.path=" + File(filamentDist, "lib").absolutePath)
        // Mesa's EGL with no display: headless software GL
        environment("EGL_PLATFORM", "surfaceless")
    }
    args(layout.buildDirectory.dir("preview").get().asFile.absolutePath, file("../../public/assets/characters").absolutePath)
}

// The town's memory check (gradle :desktop:townMemoryCheck): the app's cold start of the whole town (TownRun from the
// spawn: the ground, greenery, props, yards and all 98 buildings, then walking) at a phone's Java heap. The heap is 320 MB
// and the direct buffers (on the phone they are on the Java heap too) may take 160 MB more; with a full collection every
// 100 ms the preview measures what is truly held, and fails if the live heap and the direct buffers held at once pass
// -DheapBudget (MB, default 256: the phone's 320 MB less room for the rest of the app), or if anything runs out of memory.
if (withFilament) tasks.register<JavaExec>("townMemoryCheck") {
    group = "verification"
    description = "Builds the whole town from a cold start at a phone-sized heap and fails over the memory budget"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("PreviewKt")
    jvmArgs("-Djava.awt.headless=true", "-Xmx320m", "-XX:MaxDirectMemorySize=160m", "-XX:+ExitOnOutOfMemoryError",
        // (as many workers as the S25's cores give it)
        "-XX:ActiveProcessorCount=8",
        "-Dmode=filament-town", "-Dshots=cold", "-DcoldOnly=1", "-Dmemlive=1", "-DheapBudget=" + (System.getProperty("heapBudget") ?: "256"))
    // (-DtownYards=placeholder: the yards as flat colours, to weigh what the yard kit holds)
    System.getProperty("townYards")?.let { jvmArgs("-DtownYards=$it") }
    // (-Dtown=<folder>: another town's export, for comparing what it holds; by default the town the app ships)
    jvmArgs("-Dtown=" + (System.getProperty("town") ?: file("../app/src/main/assets/town/gamma").absolutePath))
    // (-Dmemtrace: the memory every 50 frames; -DmemOwners: the direct buffers at the peak by owner)
    for (k in listOf("world", "worldNear", "memtrace", "memOwners", "memDump", "memDumpEnd", "astc", "astcCache")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    jvmArgs("-Djava.library.path=" + File(filamentDist, "lib").absolutePath)
    environment("EGL_PLATFORM", "surfaceless")
    args(layout.buildDirectory.dir("memcheck").get().asFile.absolutePath, file("../../public/assets/characters").absolutePath)
}
