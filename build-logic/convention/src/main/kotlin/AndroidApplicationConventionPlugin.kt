import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/**
 * The version, declared once and here.
 *
 * A release is cut by pushing a `v`-prefixed tag, and the workflow refuses to publish unless the
 * tag matches this string — so the version is a reviewed change in a commit, not a property of
 * whoever typed the tag. Bump this, commit, then tag the commit.
 */
private const val VERSION_NAME = "0.0.18"

/**
 * versionCode, computed from versionName rather than stored beside it.
 *
 * Two numbers that must agree and are maintained separately eventually disagree, and the way that
 * one surfaces is an upgrade that silently does not install. Deriving it also keeps the build
 * reproducible from the tag alone, which matters more here than usual: F-Droid builds this
 * repository itself, and a versionCode taken from a CI run counter would come out different every
 * time anyone rebuilt the same source.
 *
 * `0.1.0` is 100, `1.2.3` is 10203. Two digits each for minor and patch, which is the ceiling this
 * scheme buys — a `1.2.100` would collide with `1.3.0` and is a long way off. A pre-release suffix
 * is dropped: `1.2.0-rc1` and `1.2.0` share a code, so the release supersedes its own candidate.
 */
private fun versionCodeOf(versionName: String): Int {
    val (major, minor, patch) = versionName.substringBefore('-').split('.').map(String::toInt)
    return major * 10_000 + minor * 100 + patch
}

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
                    versionName = VERSION_NAME
                    versionCode = versionCodeOf(VERSION_NAME)
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

                    // And the third name for the same idea, disabled after it
                    // did real damage rather than on principle.
                    //
                    // `GradleDependency` is `NewerVersionAvailable` under
                    // another id, and it was the one still enabled. It failed
                    // `./gradlew build` on seven "a newer version is
                    // available" errors, none of which said anything about the
                    // code; obeying it meant bumping compose-bom from
                    // 2026.06.01 to 2026.08.00, and **that shipped a release
                    // that crashed on launch** -- `ClassCastException` inside
                    // Compose's measure pass, in the minified build only, so no
                    // unit test and no debug build could see it. v0.0.10 went
                    // out broken because a linter asked for a version nobody
                    // had run.
                    //
                    // The argument above is unchanged and is now paid for: a
                    // dependency bump is a decision with a diff and a
                    // verification behind it. A checker that turns "somebody
                    // published something" into a build failure does not
                    // produce that verification -- it produces pressure to skip
                    // it.
                    disable += "GradleDependency"
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
            disableRacyUnitTestLint()
            configureUnitTestPlatform()
        }
    }
}
