import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/** The one application module. Everything else is a library. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // No org.jetbrains.kotlin.android: AGP 9 has built-in Kotlin and
            // rejects that plugin outright ("no longer required for Kotlin
            // support since AGP 9.0"). The Kotlin compiler now comes from AGP
            // itself. Only non-Android modules still apply a Kotlin plugin of
            // their own — see JvmLibraryConventionPlugin.
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                compileSdk = catalogVersionInt("compileSdk")

                defaultConfig {
                    minSdk = catalogVersionInt("minSdk")
                    targetSdk = catalogVersionInt("targetSdk")
                    versionCode = 1
                    versionName = "0.1.0"
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JAVA_TARGET
                    targetCompatibility = JAVA_TARGET
                }

                // Off by default since AGP 8.0. Enabled here rather than per
                // module because the app needs APPLICATION_ID and DEBUG, and a
                // module discovering that BuildConfig simply does not exist is
                // a confusing five minutes.
                buildFeatures.buildConfig = true

                buildTypes {
                    getByName("release") {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                    }
                    getByName("debug") { applicationIdSuffix = ".debug" }
                }

                lint {
                    // Lint's NewApi check is the only thing standing between us
                    // and an unguarded API call on the minSdk floor, since no
                    // emulator image below the compile level is installed.
                    warningsAsErrors = true
                    abortOnError = true
                    checkDependencies = true

                    // The one deliberate exception, and it is a decision rather
                    // than an oversight: targetSdk is 36 while compileSdk is 37
                    // because no API 37 emulator image has been published, so
                    // none of Android 17's behaviour changes can be run even
                    // once. Declaring a target we have never executed against
                    // would be a guess. Re-enable the moment an image ships.
                    disable += "OldTargetApi"
                }

                testOptions.unitTests {
                    isIncludeAndroidResources = true
                    isReturnDefaultValues = true
                }

                packaging.resources.excludes +=
                    setOf(
                        "/META-INF/{AL2.0,LGPL2.1}",
                        "/META-INF/LICENSE*",
                    )
            }

            // findByType, not configure: an Android module may or may not have
            // the java extension registered depending on what AGP applied, and
            // configure<> throws rather than skipping when it is absent.
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
