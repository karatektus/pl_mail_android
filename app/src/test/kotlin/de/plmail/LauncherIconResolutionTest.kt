package de.plmail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the server's three logo values resolve to, which is the whole of what this app decides about
 * a launcher icon.
 *
 * Everything else in the feature is a generated resource or a manifest attribute, checked
 * elsewhere. What is checked here is the one function with a policy in it — [LauncherIcon.resolve]
 * — and above all what it does with values it does not recognise.
 *
 * **The unknown case is not an edge case, it is the expected case.** The motifs and the paints are
 * vocabularies the server grows, and every build in the field is older than some of them. An app
 * that answered null for one would be one deploy away from having no launcher icon at all, which is
 * not a failure the user can undo from inside it. So is the *absent* case: every server older than
 * the motifs sends `logoStyle` alone, and every server older than that sends nothing.
 *
 * Plain JUnit 5, no Android: the table is generated Kotlin and the resolver is a lookup.
 */
class LauncherIconResolutionTest {

    private fun icon(motif: String, paint: String) =
        LauncherIcon.all.single { it.motif == motif && it.paint == paint }

    @Test
    fun `the pl mark wears its colourway, from the paint or from an older server's style`() {
        assertEquals(icon("pl", "ocean"), LauncherIcon.resolve("pl", "ocean", null))

        // A server from before the motifs: logoStyle and nothing else. This is
        // every install in the field on the day this ships, and it has to keep
        // the colourway it is already wearing.
        assertEquals(icon("pl", "ocean"), LauncherIcon.resolve(null, null, "ocean"))

        // Both present: the paint is the newer statement of the same choice.
        assertEquals(icon("pl", "ember"), LauncherIcon.resolve("pl", "ember", "ocean"))

        // The mark has no design of its own apart from the default colourway,
        // so its `original` is the default alias rather than a second copy of it.
        assertEquals(LauncherIcon.Default, LauncherIcon.resolve("pl", "original", "ocean"))
    }

    @Test
    fun `every other motif starts in its own design and takes a colourway when given one`() {
        assertEquals(
            "de.plmail.LogoLauncherBlueHornOriginal",
            LauncherIcon.resolve("blue-horn", null, "ocean").alias,
        )
        assertEquals(
            "de.plmail.LogoLauncherBlueHornOcean",
            LauncherIcon.resolve("blue-horn", "ocean", "berry").alias,
        )
        assertEquals(
            icon("at-horn", "blue-tonal"),
            LauncherIcon.resolve("at-horn", "blue-tonal", null),
        )
    }

    @Test
    fun `a motif this build has never heard of is the pl mark, in the paint where it can be`() {
        // A server newer than this build. The choice was a real icon; there is
        // no drawable for it in this APK, and the logo is the nearest thing.
        assertEquals(icon("pl", "ocean"), LauncherIcon.resolve("carrier-pigeon", "ocean", null))
        assertEquals(LauncherIcon.Default, LauncherIcon.resolve("carrier-pigeon", "original", null))
        assertEquals(LauncherIcon.Default, LauncherIcon.resolve("", null, null))
    }

    @Test
    fun `a paint this build has never heard of is the motif's own design, or the default`() {
        assertEquals(icon("mailbox", "original"), LauncherIcon.resolve("mailbox", "seafoam", null))

        // The pl mark keeps today's answer: an unknown colourway is berry.
        assertEquals(LauncherIcon.Default, LauncherIcon.resolve("pl", "seafoam", null))
        assertEquals(LauncherIcon.Default, LauncherIcon.resolve(null, null, "seafoam"))
        assertEquals(LauncherIcon.Default, LauncherIcon.resolve(null, "OCEAN", null))
    }

    @Test
    fun `a server that sends none of the three leaves the default in place`() {
        // The case an install is in before its first read, and on a server too
        // old for any of this. The default is the product's: the pl mark in
        // berry, on the alias the manifest enables.
        assertEquals(LauncherIcon.Default, LauncherIcon.resolve(null, null, null))
        assertEquals(
            LauncherIcon("pl", "berry", "de.plmail.LogoLauncherBerry"),
            LauncherIcon.Default,
        )
    }

    @Test
    fun `the pl mark's aliases keep the names installed phones have enabled`() {
        // The enabled state of a component is stored against its name, so a pl
        // alias renamed in this build is a phone that loses its icon in the
        // update. These are the names the thirty-two colourway aliases had
        // before there were motifs, spelled out rather than derived, because a
        // derivation is exactly what could change.
        val pl = LauncherIcon.all.filter { it.motif == LauncherIcon.PL }.map { it.alias }

        assertEquals(32, pl.size)
        listOf("ProductBlue", "Berry", "PetrolCopper", "BlueTonal", "Tricolore").forEach {
            assertTrue("de.plmail.LogoLauncher$it" in pl, "de.plmail.LogoLauncher$it is gone")
        }

        // And every alias, of every motif, under the namespace rather than the
        // applicationId. See LauncherIcon's docblock for what that costs.
        LauncherIcon.all.forEach {
            assertTrue(it.alias.startsWith("de.plmail.LogoLauncher"), it.alias)
        }
    }
}
