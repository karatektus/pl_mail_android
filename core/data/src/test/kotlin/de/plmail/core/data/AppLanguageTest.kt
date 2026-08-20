package de.plmail.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a language tag from outside this app resolves to in the picker.
 *
 * Every one of these tags arrives from somewhere the app does not control: `LocaleManager` holds
 * whatever Android's own Settings → Apps → plMail → Language wrote, and below API 33 the stored
 * copy can outlive the build that wrote it. The picker has to show what is actually in force, so
 * this is the function that decides whether it can.
 */
class AppLanguageTest {

    /** No override at all, which is what an empty `LocaleList.toLanguageTags()` comes back as. */
    @Test
    fun `nothing chosen follows the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.of(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.of(null))
    }

    @Test
    fun `the two languages the app ships`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of("en"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.of("de"))
    }

    /**
     * A region on the tag is still the same language.
     *
     * Android's own picker offers what `locales_config.xml` lists, but a value can also arrive from
     * a restore or from a newer build, and `de-AT` showing nothing selected would read as a picker
     * that had lost the user's choice.
     */
    @Test
    fun `a region is ignored`() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.of("de-DE"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.of("de-AT"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of("en-GB"))
    }

    /**
     * A per-app locale is a *list*, and the head of it is the language the app is drawn in.
     *
     * The whole string is what `LocaleList.toLanguageTags()` hands over, so this is the form the
     * caller actually has rather than one it would have to take apart first.
     */
    @Test
    fun `the first of several tags wins`() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.of("de-DE,en-US"))
    }

    /**
     * **A language this build does not ship is none of the options, not "follow the system".**
     *
     * The comfortable answer would be [AppLanguage.SYSTEM], and it would strand the user: the
     * control would draw "Follow the system" as already selected while a real override held the app
     * in a language it has no translations for, so tapping that option would change nothing and
     * there would be no way out. Null draws nothing selected, which is true, and leaves every
     * option one tap away.
     */
    @Test
    fun `a language the app does not ship is not an option`() {
        assertNull(AppLanguage.of("fr"))
        assertNull(AppLanguage.of("fr-CA"))
    }

    /**
     * A tag that is not a tag.
     *
     * It parses to the root locale with an empty language, which is the same empty string
     * [AppLanguage.SYSTEM] carries — so without the guard in [AppLanguage.of] a corrupt stored
     * value would be reported as a deliberate "follow the system".
     */
    @Test
    fun `an unparseable tag is not the system option`() {
        assertNull(AppLanguage.of("this is not a language tag"))
    }

    /** The tags are what goes to the platform, so they are worth stating rather than assuming. */
    @Test
    fun `the system option carries the empty tag the platform reads as no override`() {
        assertEquals("", AppLanguage.SYSTEM.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("de", AppLanguage.GERMAN.tag)
    }
}
