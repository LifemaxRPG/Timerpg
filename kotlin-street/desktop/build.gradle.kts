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
kotlin { jvmToolchain(21) }
tasks.test { useJUnit() }

tasks.register<JavaExec>("preview") {
    group = "verification"
    description = "Renders the street headlessly to PNG files (no phone needed)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("PreviewKt")
    jvmArgs("-Djava.awt.headless=true", "-Xmx3g")
    System.getProperty("skip")?.let { jvmArgs("-Dskip=$it", "-Donlyone=1") }
    System.getProperty("mode")?.let { jvmArgs("-Dmode=$it") }
    System.getProperty("street")?.let { jvmArgs("-Dstreet=$it") }
    System.getProperty("wx")?.let { jvmArgs("-Dwx=$it") }
    System.getProperty("season")?.let { jvmArgs("-Dseason=$it") }
    System.getProperty("hour")?.let { jvmArgs("-Dhour=$it") }
    System.getProperty("x")?.let { jvmArgs("-Dx=$it") }
    System.getProperty("junctionNoClip")?.let { jvmArgs("-DjunctionNoClip=$it") }
    System.getProperty("noLit")?.let { jvmArgs("-DnoLit=$it") }
    args(layout.buildDirectory.dir("preview").get().asFile.absolutePath, file("../../public/assets/characters").absolutePath)
}
