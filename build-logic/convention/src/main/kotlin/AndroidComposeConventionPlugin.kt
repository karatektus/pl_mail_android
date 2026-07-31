import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Compose, on top of whichever Android plugin the module already applied.
 *
 * Separate from the application/library conventions because not every module has a UI —
 * `:core:jmap` and `:core:data` must never pull Compose in, and the cheapest way to guarantee that
 * is to make it opt-in.
 *
 * Since Kotlin 2.0 the Compose compiler ships with Kotlin itself, so there is no separate compiler
 * version to keep in step with the Kotlin version — the single commonest way a Compose build used
 * to break.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            // The two concrete extension types rather than a star-projected
            // CommonExtension: that generic's arity has changed between AGP
            // releases, and getting it wrong fails to compile for a reason
            // that never mentions AGP.
            val application = extensions.findByType(ApplicationExtension::class.java)
            val library = extensions.findByType(LibraryExtension::class.java)

            when {
                application != null -> application.buildFeatures.compose = true
                library != null -> library.buildFeatures.compose = true
                else ->
                    error(
                        "plmail.android.compose needs an Android plugin first — apply " +
                            "plmail.android.library or plmail.android.application above it."
                    )
            }

            dependencies {
                val bom = libs.findLibrary("androidx-compose-bom").get()

                add("implementation", platform(bom))
                add("implementation", libs.findLibrary("androidx-compose-ui").get())
                add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
                add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
                add("implementation", libs.findLibrary("androidx-compose-material3").get())

                add("androidTestImplementation", platform(bom))
                add(
                    "androidTestImplementation",
                    libs.findLibrary("androidx-compose-ui-test-junit4").get(),
                )

                // ui-tooling and the test manifest are debug-only on purpose:
                // both drag tooling code into the APK, and neither has any
                // business in a release build.
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
                add(
                    "debugImplementation",
                    libs.findLibrary("androidx-compose-ui-test-manifest").get(),
                )
            }
        }
    }
}
