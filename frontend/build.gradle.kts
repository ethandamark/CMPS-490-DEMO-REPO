// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    id("com.google.gms.google-services") version "4.4.0" apply false
}

val externalBuildRoot = providers.environmentVariable("LOCALAPPDATA")
    .orNull
    ?.let { file("$it/CMPS490-FrontendRepo/build") }
    ?: file("${System.getProperty("java.io.tmpdir")}/CMPS490-FrontendRepo/build")

subprojects {
    layout.buildDirectory.set(externalBuildRoot.resolve(name))
}