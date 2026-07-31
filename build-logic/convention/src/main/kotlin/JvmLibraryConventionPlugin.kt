import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/**
 * A plain JVM module — no Android at all.
 *
 * `:core:jmap` is the reason this exists. It is the highest-test-value code in the project (every
 * wire shape the server can hand back), and keeping the Android frameworks out of it means its
 * suite runs on the host in seconds rather than booting an emulator. The module boundary is what
 * enforces that: an accidental `import android.*` fails to compile instead of quietly making the
 * fastest loop in the project the slowest.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(javaToolchainVersion())
            }

            configureKotlin()
            configureUnitTestPlatform()
        }
    }
}
