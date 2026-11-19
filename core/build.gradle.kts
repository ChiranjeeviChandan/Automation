import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

// Bytecode level 17 (Android-compatible) without pinning a specific JDK install.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    listOf("verity.input", "verity.output", "verity.faces").forEach { k -> (project.findProperty(k) as String?)?.let { systemProperty(k, it) } }
    testLogging { showStandardStreams = true; events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
