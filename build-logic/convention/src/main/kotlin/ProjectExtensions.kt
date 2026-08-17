import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * The version catalog, reachable from a plugin class.
 *
 * Build scripts get a generated `libs` accessor; precompiled plugin *classes* do not, so this is
 * how a convention plugin reads the same catalog the modules it configures are reading. Without it
 * every version would be declared twice.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.catalogVersion(name: String): String =
    libs.findVersion(name).get().requiredVersion

internal fun Project.catalogVersionInt(name: String): Int = catalogVersion(name).toInt()

/**
 * Java 17 bytecode from a JDK 21 toolchain.
 *
 * The toolchain is what compiles; the target is what Android runs. Pinning the toolchain rather
 * than inheriting `JAVA_HOME` means the build produces the same bytecode on a machine with a
 * different JDK installed, which is the whole point of declaring one.
 */
internal val JAVA_TARGET = JavaVersion.VERSION_17

internal fun Project.javaToolchainVersion(): JavaLanguageVersion =
    JavaLanguageVersion.of(catalogVersionInt("jdk"))

/**
 * Kotlin compiler settings shared by every module.
 *
 * Warnings are errors by default, because a warning nobody fails on is a warning nobody reads.
 * `-Pplmail.warningsAsErrors=false` turns it off for the one case that justifies it: bisecting
 * against a new compiler version that has started warning about something unrelated to the change
 * under test.
 */
internal fun Project.configureKotlin() {
    val warningsAsErrors =
        providers.gradleProperty("plmail.warningsAsErrors").orNull?.toBoolean() ?: true

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(warningsAsErrors)
        }
    }
}

/**
 * JUnit 5 for unit tests, in Android modules too.
 *
 * Android's `testDebugUnitTest` is an ordinary Gradle `Test` task, so it takes `useJUnitPlatform()`
 * directly and needs no third-party plugin — only the engine on the test runtime classpath. Worth
 * knowing, because the usual advice is to reach for one. (Instrumented `androidTest` is a different
 * runner entirely and stays on JUnit 4.)
 */
internal fun Project.configureUnitTestPlatform() {
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

/**
 * Turns off lint's *unit-test* analysis, which races across variants and fails the build at random.
 *
 * ## What actually happens
 *
 * Lint resolves symbols through generated Java, and the source roots AGP hands its unit-test
 * analysis reach into **other variants**. Two caught in the act, both from daemon logs rather than
 * guessed at:
 * ```
 * :core:data:lintAnalyzeDebugUnitTest
 *   core/data/build/kspCaches/release/backups/.../AccountClients_Factory.java (No such file)
 * :app:lintAnalyzeFossDebugUnitTest
 *   app/build/generated/hilt/component_sources/fossRelease/.../_de_plmail_PlMailApplication.java
 * ```
 *
 * A *debug* analysis reading a *release* KSP scratch directory. `./gradlew build` assembles both
 * variants, `org.gradle.parallel` runs them together, and the release KSP and Hilt tasks rewrite
 * those files while the debug analysis is part-way through reading them. The file disappears
 * mid-read, lint reports "Unexpected failure during lint analysis (this is a bug in lint or one of
 * the libraries it depends on)", and the task fails — on nothing anybody changed, never twice in
 * the same place, and green on the very next run. Which is the worst possible shape for a gate: it
 * teaches people to re-run rather than to read.
 *
 * ## Why disabling is the fix rather than a workaround
 *
 * Neither path is a source directory. `kspCaches/…/backups` is KSP's own scratch, and the other is
 * a sibling variant's generated output — the analysis was reading files it has no business
 * resolving against, so the coverage it bought was never real. `lint { checkTestSources = false }`
 * looks like the switch for this and is not: it gates whether findings are *reported*, and the
 * tasks are created and run either way (verified — the task graph is unchanged by it).
 *
 * **Main-source lint is untouched.** `warningsAsErrors`, `abortOnError` and `checkDependencies` all
 * still hold, and every finding this project has ever acted on — NonObservableLocale,
 * PluralsCandidate, MissingOnRenderProcessGone, the GradleDependency set — came from main sources.
 *
 * Delete this the day AGP stops putting one variant's generated output on another's analysis path.
 */
internal fun Project.disableRacyUnitTestLint() {
    tasks.configureEach {
        if (name.startsWith("lintAnalyze") && name.endsWith("UnitTest")) enabled = false
    }
}
