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

// The crossroads' own tests (the camera, the ground plan, the walk round the corner), in a set of their own: the habit
// tracker's suite under src/test needs its data layer, which this repo does not have, so it does not compile here.
// gradle :desktop:crossroadsTest
val crossroadsTest by sourceSets.creating {
    kotlin.srcDir("src/crossroadsTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
dependencies { "crossroadsTestImplementation"("junit:junit:4.13.2") }
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
    jvmArgs("-Djava.awt.headless=true", "-Xmx3g")
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
    // the crossroads and the seam test: where along the mouth, which screen, which way round, the whole sequence, a name
    for (k in listOf("at", "size", "dir", "seq", "tag", "shots"))
        System.getProperty(k)?.let { jvmArgs("-D$k=$it") }
    args(layout.buildDirectory.dir("preview").get().asFile.absolutePath, file("../../public/assets/characters").absolutePath)
}
