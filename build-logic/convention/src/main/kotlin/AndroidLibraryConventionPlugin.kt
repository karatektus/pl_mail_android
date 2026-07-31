import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/**
 * An Android library module.
 *
 * Note there is no `targetSdk` here: a library has no say in what the app it ends up in targets,
 * and AGP ignores it. Only `:app` declares one.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // See AndroidApplicationConventionPlugin: AGP 9 supplies Kotlin.
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = catalogVersionInt("compileSdk")

                defaultConfig {
                    minSdk = catalogVersionInt("minSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JAVA_TARGET
                    targetCompatibility = JAVA_TARGET
                }

                lint {
                    warningsAsErrors = true
                    abortOnError = true
                }

                testOptions.unitTests {
                    isIncludeAndroidResources = true
                    isReturnDefaultValues = true
                }
            }

            // See AndroidApplicationConventionPlugin for why findByType.
            extensions
                .findByType(JavaPluginExtension::class.java)
                ?.toolchain
                ?.languageVersion
                ?.set(javaToolchainVersion())

            configureKotlin()
            configureUnitTestPlatform()
        }
    }
}
