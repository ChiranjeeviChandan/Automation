import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(project(":core"))
    implementation("com.anthropic:anthropic-java:3.3.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true; events("passed", "failed") }
}
