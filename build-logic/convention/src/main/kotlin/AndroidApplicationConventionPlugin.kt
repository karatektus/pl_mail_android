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

                    // A build that fails on the calendar rather than on a
                    // change. `AndroidGradlePluginVersion` compares the
                    // wrapper's Gradle against whatever has been published
                    // *today*, so a green build becomes a red one overnight
                    // with nothing in the repo having moved -- and with
                    // `warningsAsErrors` on, red means `./gradlew build` stops.
                    // It reported nothing about this code even when it fired:
                    // the whole finding was that Gradle 9.7.0 now exists.
                    //
                    // Upgrading is a real decision with a real diff behind it
                    // -- the last time this project's Gradle moved it left
                    // stale transform snapshots that read as a corrupt
                    // dependency, see REMAINING.md -- and it belongs in a
                    // commit that says so, not in whichever build happens to
                    // run the morning after a release.
                    disable += "AndroidGradlePluginVersion"

                    // The same finding about a different file, and disabled for
                    // exactly the same reason. `NewerVersionAvailable` compares
                    // `gradle/libs.versions.toml` against whatever Maven Central
                    // holds *today*, so `./gradlew build` went red on this
                    // branch citing line 47 -- a line nothing in the branch had
                    // touched -- because junit-bom 6.1.3 had been published
                    // since the last green build. With `warningsAsErrors` on
                    // that stops the build, and it says nothing whatever about
                    // the code being built.
                    //
                    // Bumping a dependency is a decision with a diff behind it
                    // and belongs in a commit that says so. What is lost is a
                    // nudge; what is bought is a build whose colour depends on
                    // this repository rather than on the release calendar.
                    disable += "NewerVersionAvailable"
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
