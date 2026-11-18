pluginManagement {
    repositories {
        // With an Android SDK: Google's Maven in full. Without one (JVM-only builds behind restrictive
        // proxies): only Android groups, so other lookups never hit a blocked host.
        if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
            file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }) google() else google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // With an Android SDK: Google's Maven in full. Without one (JVM-only builds behind restrictive
        // proxies): only Android groups, so other lookups never hit a blocked host.
        if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
            file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }) google() else google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "VerityCamera"

// Pure-JVM modules: the colour pipeline and the Claude "director". Build and test anywhere.
include(":core", ":director", ":inpaint")

// Android app: only included when an Android SDK is available, so the JVM modules
// stay buildable on CI boxes / containers without one.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) include(":app")
