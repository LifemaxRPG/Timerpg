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
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnit() }

tasks.register<JavaExec>("preview") {
    group = "verification"
    description = "Renders the street headlessly to PNG files (no phone needed)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("PreviewKt")
    jvmArgs("-Djava.awt.headless=true", "-Xmx" + (System.getProperty("xmx") ?: "3g"))
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
    for (k in listOf("turn", "rest", "sky", "look", "eye", "back", "w")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the street props' preview: which half (lineup, street) and which rows of the town
    for (k in listOf("props", "rows")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the yards' preview: which half (lineup, real)
    for (k in listOf("yards")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the movie theater's preview (-Dmode=filament-cinema): which eras, or posters
    for (k in listOf("cinema", "cinemaBills")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the stores' preview (-Dmode=filament-stores): which variants or kinds
    System.getProperty("stores")?.let { jvmArgs("-Dstores=$it") }
    System.getProperty("storesFrom")?.let { jvmArgs("-DstoresFrom=${File(it).absolutePath}") }
    System.getProperty("storesYears")?.let { jvmArgs("-DstoresYears=$it") }
    System.getProperty("weekday")?.let { jvmArgs("-Dweekday=$it") }
    System.getProperty("ref")?.let { jvmArgs("-Dref=$it") }
    // the landmark models' preview (-Dmode=filament-landmarks): which models, which year
    for (k in listOf("landmarks", "year")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the whole town's preview (-Dmode=filament-town): which town folder, where the pack's pictures are, the yards'
    // builder (kit, or placeholder: flat colours)
    for (k in listOf("town", "assetsRoot", "townYards", "memtrace", "heapBudget", "memlive", "coldOnly")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    // the open world's preview (-Dmode=filament-open): -DtownLod=0 bakes no far yards (to compare)
    for (k in listOf("townLod", "townFar", "folk", "see", "farmTag", "farms", "debugeye", "bench", "benchSecs", "gpucost", "rail", "glbAs", "glbTag", "glbFile")) System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
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
    jvmArgs("-Djava.library.path=" + File(filamentDist, "lib").absolutePath)
    environment("EGL_PLATFORM", "surfaceless")
    args(layout.buildDirectory.dir("memcheck").get().asFile.absolutePath, file("../../public/assets/characters").absolutePath)
}
