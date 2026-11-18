import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

val ort = "1.20.0"
dependencies {
    api(project(":core"))
    // Same `ai.onnxruntime` API on both platforms: the app supplies onnxruntime-android,
    // JVM tests use the desktop build.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ort")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ort")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging { showStandardStreams = true; events("passed", "failed") }
}
