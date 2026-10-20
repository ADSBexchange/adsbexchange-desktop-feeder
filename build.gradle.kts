buildscript {
    // Lock the build's own plugins (Kotlin, Compose) as well as the app's dependencies; see README.
    configurations.classpath { resolutionStrategy.activateDependencyLocking() }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}
