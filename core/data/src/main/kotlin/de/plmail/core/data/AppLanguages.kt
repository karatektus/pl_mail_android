package de.plmail.core.data

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's language, asked about and chosen.
 *
 * **There is no flow and nothing of ours is stored on API 33 and up**, for the reason
 * [CalendarLauncherIcon] gives about the launcher alias: from Tiramisu the per-app language is a
 * fact the *platform* owns, editable from Android's own Settings → Apps → plMail → Language as well
 * as from here, and a copy in DataStore would be a second answer that disagrees with the system's
 * silently. So this is read on demand and re-read when a screen comes back into view, rather than
 * subscribed to — the platform publishes no change signal for it either.
 *
 * The implementation is [SystemAppLanguages]. It is an interface anyway, because a view model that
 * held `LocaleManager` directly could not be tested off a device.
 */
interface AppLanguages {

    /**
     * Whether choosing a language takes effect without this app restarting anything itself.
     *
     * True from API 33, where the platform treats the per-app locale as a configuration change and
     * re-creates the activities for us. False below it, where nothing is watching and the caller
     * has to re-create the activity it is drawn in — see [AppLocaleOverride].
     */
    val isAppliedBySystem: Boolean

    /**
     * The language in force, or null when it is one this build does not offer.
     *
     * Asked of the platform every time on API 33+, so a language set from Android's own settings
     * screen is what a picker here shows. See [AppLanguage.of] for why null rather than
     * [AppLanguage.SYSTEM].
     */
    fun current(): AppLanguage?

    /** Applies the choice. Synchronous, and the caller re-reads rather than assuming it took. */
    fun choose(language: AppLanguage)
}

/**
 * The per-app language, through the platform above API 33 and through [AppLocaleOverride] below it.
 *
 * **Two mechanisms rather than `AppCompatDelegate.setApplicationLocales`, and the reason is not
 * dependency weight.** `androidx.appcompat` is already on this app's runtime classpath —
 * camera-view drags in 1.6.1 for the pairing scanner — so adding it would have cost nothing in APK
 * size. It is not used because on API 31 and 32 its implementation applies the locale by walking
 * the `AppCompatDelegate`s that `AppCompatActivity` registers, and this app has none: both
 * activities are `ComponentActivity` under `Theme.PlMail`, a platform `DeviceDefault.DayNight`
 * theme, and `AppCompatActivity` refuses to start under anything that is not a `Theme.AppCompat`.
 * Adopting it would have meant an AppCompat theme and AppCompat's view inflation for an app that
 * draws no views, to reach a code path that also needs its own manifest opt-in before it persists
 * anything. The library would have been carried for the two API levels it cannot help with as
 * written.
 */
@Singleton
class SystemAppLanguages
@Inject
constructor(@param:ApplicationContext private val context: Context) : AppLanguages {

    override val isAppliedBySystem: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * The version check is spelled out here rather than read from [isAppliedBySystem], and the two
     * are the same test.
     *
     * Lint's `NewApi` follows an inline `Build.VERSION.SDK_INT` comparison and nothing else: behind
     * a property it sees only a `Boolean`, and `LocaleManager` becomes an unguarded API 33 class
     * reference in a module whose `minSdk` is 31. The duplication is what makes the guard visible
     * to the check that exists to catch a missing one.
     */
    override fun current(): AppLanguage? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            AppLanguage.of(locales().applicationLocales.toLanguageTags())
        else AppLanguage.of(AppLocaleOverride.stored(context))

    /**
     * `AppBundleLocaleChanges` is suppressed, and the thing it warns about is genuinely handled.
     *
     * The check fires on any dynamic locale change and asks for either Play Feature Delivery calls
     * to download the language, or a bundle that does not split by language. `:app` takes the
     * second of those — `bundle.language.enableSplit = false` — and this module cannot see that
     * from here, so the suppression is local to the call and the fix is not.
     */
    @SuppressLint("AppBundleLocaleChanges")
    override fun choose(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // An empty list rather than a null: that is how the platform spells
            // "no override, follow the system", and it is what makes the entry
            // in Android's own settings read "System default" again.
            locales().applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            AppLocaleOverride.store(context, language.tag)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun locales(): LocaleManager = context.getSystemService(LocaleManager::class.java)
}

/**
 * The API 31 and 32 half of the per-app language, which the platform has no feature for.
 *
 * Those two levels are inside `minSdk` and there is no `LocaleManager` on them, so the choice is
 * kept here and laid over each context as it is created. Every entry point into the process wraps
 * its own base context — the application in `PlMailApplication.attachBaseContext` and both
 * activities in theirs — because an activity's configuration comes from the ActivityThread rather
 * than from the application's, so wrapping one does not reach the other.
 *
 * **What this covers.** It survives process death, because the tag is read from disk on every
 * attach rather than held in memory. It reaches everything resolved through a wrapped context,
 * which after a cold start is the whole app: the activities, and the application context that
 * `:core:notifications` resolves its notification text and channel names from.
 *
 * **What it does not.** A language chosen while the app is running reaches the activity at once,
 * because the screen re-creates itself, and reaches the *application* context only at the next cold
 * start — an already-attached context cannot have its configuration changed without
 * `Resources.updateConfiguration`, which is deprecated and reaches into resources the framework
 * owns. So on API 31 and 32 only, a notification posted between the change and the next process
 * start can still carry the previous language. From API 33 the platform applies the locale to the
 * app's own configuration and none of this applies.
 *
 * **SharedPreferences and not DataStore**, which is the one place in this codebase that reaches for
 * it. `attachBaseContext` runs before the first frame and before Hilt has a graph to inject from,
 * and it has to answer synchronously; a DataStore read there would be a `runBlocking` on the main
 * thread in front of every cold start, which is precisely the hazard `CredentialStore` documents.
 * Its own file rather than the shared preferences file for the same reason — nothing here may
 * depend on the credential store having been opened.
 */
object AppLocaleOverride {

    /**
     * Lays the chosen language over a base context, or hands it back untouched.
     *
     * Untouched on API 33 and up, where the platform has already done this and doing it again would
     * mean two overrides that can disagree — the system's, and a stale copy of ours.
     *
     * [Locale.setDefault] beside the configuration, because the two answer different questions. The
     * configuration decides which `values-*` directory a string comes from; the default locale
     * decides how `java.time` and `NumberFormat` render a date or a number, and a German app
     * printing English weekday names would be half translated.
     *
     * `AppBundleLocaleChanges` is suppressed for the reason [SystemAppLanguages.choose] gives: the
     * bundle this warns about is configured not to split by language, in `:app`, which is not
     * somewhere this module's lint can look.
     */
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base

        // Null is a tag this build does not offer and SYSTEM is no override;
        // both mean "leave the context alone", which is also what makes an
        // unknown stored value degrade to the system language rather than fail.
        val language = AppLanguage.of(stored(base))
        if (language == null || language == AppLanguage.SYSTEM) return base

        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)

        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(locale))

        return base.createConfigurationContext(configuration)
    }

    /** The stored tag, or null while nobody has chosen. Never decoded here — see [AppLanguage]. */
    internal fun stored(context: Context): String? = preferences(context).getString(TAG, null)

    internal fun store(context: Context, tag: String) {
        preferences(context).edit { putString(TAG, tag) }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "app_locale"

    private const val TAG = "language_tag"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppLanguagesModule {

    @Binds @Singleton abstract fun languages(real: SystemAppLanguages): AppLanguages
}
