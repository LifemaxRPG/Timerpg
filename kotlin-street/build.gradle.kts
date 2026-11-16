// Root: plugin versions only. Modules: :desktop (JVM, renders frames headlessly for checks) and later :app (Android).
plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.1" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
