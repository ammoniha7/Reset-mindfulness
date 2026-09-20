// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("androidx.room") version "2.8.5" apply false
    // Register KSP here so the app module can find it
    id("com.google.devtools.ksp") version "2.3.6" apply false
}