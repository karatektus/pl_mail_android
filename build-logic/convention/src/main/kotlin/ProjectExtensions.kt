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
