package de.plmail.core.data

import java.util.Locale

/**
 * The language the app draws itself in.
 *
 * Exactly the languages this build actually ships — `values/` and `values-de/` — plus the option of
 * having no opinion at all. Adding a member here without adding the translations, or without adding
 * the locale to `res/xml/locales_config.xml`, gives the user a control that switches to English
 * silently; `LocalesConfigTest` in `:app` is what keeps the three lists from drifting apart.
 *
 * **[SYSTEM] carries the empty tag rather than a null**, because the empty tag is what both sides
 * of the platform already spell "no override": `LocaleList.forLanguageTags("")` is the empty list
 * that clears `LocaleManager.applicationLocales`, and an empty stored string is the same absence
 * below API 33. One value that means the same thing everywhere is one fewer place to write `?:` and
 * accidentally turn "follow the system" into "English".
 *
 * A language name is written in its own language — "Deutsch", not "German" — which is why the
 * labels for these are the same string in both `values/` and `values-de/`. Somebody looking for
 * their own language in a list is looking for the word they use for it.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    GERMAN("de");

    companion object {

        /**
         * Which option a stored or platform-held language tag corresponds to, or null for none.
         *
         * **Null is a real answer and not an error.** It means the app is set to a language this
         * build does not offer — the residue of a version that shipped one more, or a value written
         * by a newer build onto an older one. Folding that case into [SYSTEM] would be the
         * comfortable choice and it is a trap: the control would show "Follow the system" already
         * selected while the platform holds a genuine override, so tapping it would do nothing and
         * there would be no way out of the wrong state at all. Nothing selected is the truth, and
         * every option is one tap away from fixing it.
         *
         * [tags] is comma separated because that is what it arrives as from both sources —
         * `LocaleList.toLanguageTags()` and the stored copy of it. Only the first is consulted: a
         * per-app locale list is a preference order and the head of it is the language the app will
         * actually be drawn in.
         *
         * Matched on the language subtag rather than on the whole tag, so `de-AT` and `de-DE` are
         * both German. A user who set their app language from a system picker that offered a region
         * would otherwise fall into the null branch above and find nothing selected.
         */
        fun of(tags: String?): AppLanguage? {
            if (tags.isNullOrEmpty()) return SYSTEM

            // Ill-formed tags come back from forLanguageTag as the root locale
            // with an empty language, which would otherwise match SYSTEM's own
            // empty tag and report a broken value as a deliberate one.
            val language = Locale.forLanguageTag(tags.substringBefore(',')).language
            if (language.isEmpty()) return null

            return entries.firstOrNull { it.tag == language }
        }
    }
}
