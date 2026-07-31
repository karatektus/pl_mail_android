plugins { `kotlin-dsl` }

group = "de.plmail.buildlogic"

java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt()) } }

dependencies {
    // compileOnly, not implementation: these plugins are on the consuming
    // build's classpath already. Bundling them here would put two copies of
    // AGP on one classpath, which fails in ways that do not name AGP.
    compileOnly(libs.agp.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.kotlin.compose.gradle.plugin)
    compileOnly(libs.spotless.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "plmail.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "plmail.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "plmail.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "plmail.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
