// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Register KSP here so the app module can find it
    id("com.google.devtools.ksp") version "2.3.2" apply false
}