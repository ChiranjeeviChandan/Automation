// AGP is put on the root build classpath only when an Android SDK is present, so the pure-JVM
// modules (:core, :director) still build on machines that cannot reach Google's Maven repository.
buildscript {
    val sdkPresent = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
        rootDir.resolve("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    repositories {
        if (sdkPresent) google()
        mavenCentral()
    }
    if (sdkPresent) dependencies { classpath("com.android.tools.build:gradle:8.7.3") }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
