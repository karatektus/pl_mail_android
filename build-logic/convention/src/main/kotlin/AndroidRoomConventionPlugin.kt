import androidx.room.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Room, with its generated schemas checked in.
 *
 * `schemaDirectory` is what makes migrations testable: Room writes a JSON description of every
 * version, and the migration test opens an old one and upgrades it. Without the files there is
 * nothing to migrate *from*, and a migration test can only assert that the current schema equals
 * itself.
 *
 * They are checked into git deliberately — a schema that only exists in a build directory is gone
 * the first time anyone runs `clean`.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("androidx.room")
            pluginManager.apply("com.google.devtools.ksp")

            extensions.configure<RoomExtension> { schemaDirectory("$projectDir/schemas") }

            dependencies {
                add("implementation", libs.findLibrary("androidx-room-runtime").get())
                add("implementation", libs.findLibrary("androidx-room-ktx").get())
                add("ksp", libs.findLibrary("androidx-room-compiler").get())
            }
        }
    }
}
