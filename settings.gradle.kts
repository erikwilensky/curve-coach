pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Versions live here so the Android plugin is only downloaded when the app is built.
        id("com.android.application") version "8.7.3"
        kotlin("android") version "2.1.0"
        kotlin("jvm") version "2.1.0"
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "curve-coach"
include(":core")
// The Android app needs the Android SDK. Leave it out where there is none,
// so the core logic can still be built and tested anywhere.
val hasSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (hasSdk) include(":app")
