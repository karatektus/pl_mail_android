plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

/**
 * One Spotless configuration for the whole tree, applied at the root rather than per module.
 * Formatting is not a per-module opinion, and a module that forgot to apply the plugin would
 * silently stop being checked.
 *
 * `kotlinlangStyle` rather than ktfmt's `googleStyle`: the Android Kotlin style guide follows the
 * Kotlin coding conventions, which are 4-space indented. ktfmt's google style is 2-space — correct
 * for Google-internal Kotlin, wrong for Android, and it disagrees with the
 * `kotlin.code.style=official` that Studio reads from gradle.properties.
 */
spotless {
    val ktfmtVersion = libs.versions.ktfmt.get()

    // Never anything under `.claude/`, which is not part of the project. It is
    // gitignored tooling scratch space and it can contain whole git worktrees of
    // this same repository -- so without this the root project formats, and
    // fails on, source files that belong to a different checkout entirely. A
    // build that goes red because of a file `git status` does not even list is
    // a build nobody can debug.
    val outsideTheProject = arrayOf("**/build/**", ".claude/**")

    kotlin {
        target("**/*.kt")
        targetExclude(*outsideTheProject)
        ktfmt(ktfmtVersion).kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(*outsideTheProject)
        ktfmt(ktfmtVersion).kotlinlangStyle()
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {
        target("**/*.md", "**/*.yml", "**/*.yaml", "**/.gitignore")
        targetExclude(*outsideTheProject)
        trimTrailingWhitespace()
        endWithNewline()
    }
}
