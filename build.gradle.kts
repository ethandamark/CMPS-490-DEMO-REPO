// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val externalBuildRoot = providers.environmentVariable("LOCALAPPDATA")
    .orNull
    ?.let { file("$it/WeatherMCPApp/build") }
    ?: file("${System.getProperty("java.io.tmpdir")}/WeatherMCPApp/build")

subprojects {
    layout.buildDirectory.set(externalBuildRoot.resolve(name))
}