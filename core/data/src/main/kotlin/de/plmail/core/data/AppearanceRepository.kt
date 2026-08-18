package de.plmail.core.data

import de.plmail.core.datastore.AppearanceStore
import de.plmail.core.datastore.DensityOverride
import de.plmail.core.datastore.RemoteAppearance
import de.plmail.core.datastore.StoredAppearance
import de.plmail.jmap.methods.Appearance
import de.plmail.jmap.methods.AppearanceGet
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.Session
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * Appearance as one loose record, ready for the design system's resolver.
 *
 * Strings rather than enums all the way up to the ViewModel, so `PlMailAppearance.of(...)` stays
 * the single place a wire value becomes a type. That is the swap the plan promised would touch the
 * resolver and nothing else, and this is what it swapped the resolver's *source* to.
 */
data class AppearanceSettings(
    val theme: String? = null,
    val layout: String? = null,
    val density: String? = null,
    val paneAlpha: String? = null,
    val dynamicColor: Boolean = false,
    val reduceTransparency: Boolean = false,
    val syncWithServer: Boolean = true,
    val accountCorner: Boolean? = null,
    val listAvatars: Boolean? = null,
    val previewLines: Int? = null,
    val unreadEmphasis: String? = null,
    val fontFamily: String? = null,
    val fontScale: Float? = null,
    /**
     * Null means this surface follows [density], which is the server's own answer rather than an
     * absence — see the note on [resolve] for the `?:` that would collapse the two.
     */
    val sidebarDensity: String? = null,
    val listDensity: String? = null,
    val readingDensity: String? = null,
)

/**
 * The user's appearance: the account's, with this phone's own choices over the top.
 *
 * **This app never writes an appearance to the server, in any mode.** Not when the sync is on, not
 * on the way to turning it off, not once. Theming is a per-device matter — a phone in one hand at
 * night and a browser on a desk are not the same surface and have no business overruling each other
 * — and the earlier design had the phone push `Appearance/set`, so picking a darker theme on the
 * train restyled the browser somebody else was working in. That is the whole reason `attemptFlush`
 * and its `stateMismatch` retry are gone rather than merely gated: a write path that exists is a
 * write path that gets called.
 *
 * **So the server is the base and this device layers on it.** A local choice goes to DataStore
 * immediately — the app re-themes under the finger, with no round trip and nothing to fail — and
 * stays there. Anything the user has *not* chosen here reads through to the account's value, which
 * is what makes a theme picked in the browser show up on the phone at all.
 *
 * **There is no `Appearance/changes` and no push**, so remote changes are picked up on sync and on
 * foreground, and never polled for. A theme somebody changed on their laptop appears on the phone
 * the next time the phone looks, which for this product is the next time it is unlocked.
 *
 * **[setSyncWithServer] governs the one remaining direction.** With it off, [refresh] returns
 * before it reads, so the account's appearance is frozen at whatever was last seen and the phone is
 * wholly its own. Turning it back on drops every local override and re-reads — which is the reset
 * button, and the only way back to "whatever the browser says". Nothing else changes shape, which
 * is why an off switch is one early return rather than a second code path.
 *
 * The server holds seven themes, background images, ink overrides and a blur this app cannot
 * render. None of that is at risk now: with nothing ever sent, `paper` and the rest survive by
 * construction rather than by a patch builder being careful about which properties it names.
 */
@Singleton
class AppearanceRepository
@Inject
constructor(private val store: AppearanceStore, private val clients: AccountClients) {

    /**
     * What the app should look like: the server's copy with this device's pending changes on top.
     */
    val settings: Flow<AppearanceSettings> =
        combine(store.appearance, store.remote) { local, remote -> resolve(local, remote) }
            .distinctUntilChanged()

    suspend fun setTheme(wire: String) = local { store.setTheme(wire) }

    suspend fun setLayout(wire: String) = local { store.setLayout(wire) }

    suspend fun setDensity(wire: String) = local { store.setDensity(wire) }

    suspend fun setPaneAlpha(alpha: Float) = local { store.setPaneAlpha(alpha) }

    suspend fun setAccountCorner(shown: Boolean) = local { store.setAccountCorner(shown) }

    suspend fun setListAvatars(shown: Boolean) = local { store.setListAvatars(shown) }

    suspend fun setPreviewLines(lines: Int) = local { store.setPreviewLines(lines) }

    suspend fun setUnreadEmphasis(wire: String) = local { store.setUnreadEmphasis(wire) }

    suspend fun setFontFamily(wire: String) = local { store.setFontFamily(wire) }

    suspend fun setFontScale(scale: Float) = local { store.setFontScale(scale) }

    suspend fun setSidebarDensity(override: DensityOverride) = local {
        store.setSidebarDensity(override)
    }

    suspend fun setListDensity(override: DensityOverride) = local {
        store.setListDensity(override)
    }

    suspend fun setReadingDensity(override: DensityOverride) = local {
        store.setReadingDensity(override)
    }

    /** Local-only. Material You is an Android answer to a question the server does not ask. */
    suspend fun setDynamicColor(enabled: Boolean) = store.setDynamicColor(enabled)

    /** Local-only. Android publishes no reduce-transparency setting to inherit. See the screen. */
    suspend fun setReduceTransparency(enabled: Boolean) = store.setReduceTransparency(enabled)

    /**
     * Whether this phone follows the account's appearance, or has one of its own.
     *
     * **Turning it off sends nothing.** Not "sends a final state" and not "flushes what is
     * pending": the very next thing that happens is that [flush] starts returning early, so an
     * override that had not reached the server when the switch was thrown never does. That is the
     * promise the switch's own supporting text makes — the web is left exactly as its owner last
     * had it — and it is why the flag is written before anything else looks at it.
     *
     * **Turning it back on is server-wins, and it is server-wins by construction.** Every override
     * is dropped first and only then is the server read, so there is no merge to get subtly wrong
     * and no window in which a month of local divergence could be flushed into the browser. The
     * three local-only flags survive, because they are not the server's to answer.
     */
    suspend fun setSyncWithServer(enabled: Boolean) {
        store.setSyncWithServer(enabled)

        if (!enabled) return

        store.clearAllOverrides()
        refresh()
    }

    /**
     * Reads the account's appearance. Nothing goes the other way.
     *
     * Called on sync and on foreground. Failures are swallowed: nothing here is an operation the
     * user asked for, and a throw from a sync scope would take the sync down over a theme.
     */
    suspend fun refresh() {
        try {
            // Before the client is even looked up. This is the read half of the
            // switch, and a phone running its own appearance must not have the
            // browser's values land on top of it every time a sync fires.
            if (!store.appearance.first().syncWithServer) return

            val client = clients.current() ?: return
            val session = client.session()
            if (session.appearance == null) return

            primeFromSession(session)

            val request = RequestBuilder(using = Capability.USING_APPEARANCE)
            val get = request.add(AppearanceGet())
            val result = client.send(request).result(get)

            result.appearance?.let { store.setRemote(it.toStored(result.state)) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // See the docblock. A theme is not worth failing a sync over, and
            // this device's own appearance is on disk either way -- an
            // unreachable server costs the account's values, not the user's.
        }
    }

    /**
     * Fills in what the session already answered, for the fields nothing has read yet.
     *
     * The capability carries a compact `{theme, layout, accent, density}` beside the vocabularies,
     * and discovery has already happened before anything is drawn — so this is a correct first
     * paint for free, where `Appearance/get` costs a round trip. Gaps only: the stored record came
     * from the authoritative read and the hint must not overwrite it with three of its four fields.
     */
    private suspend fun primeFromSession(session: Session) {
        val hint = session.appearance?.hint ?: return
        val remote = store.remote.first()

        val primed =
            remote.copy(
                theme = remote.theme ?: hint.theme,
                layout = remote.layout ?: hint.layout,
                density = remote.density ?: hint.density,
            )

        if (primed != remote) store.setRemote(primed)
    }

    /**
     * Writes the choice, and that is the whole of it.
     *
     * A wrapper around one call rather than the call itself, because it used to send as well and
     * the name is the reminder that it must not again: every appearance setter goes through here,
     * so there is exactly one place to look to be sure nothing leaves the device.
     */
    private suspend fun local(write: suspend () -> Unit) = write()

    private companion object {

        fun Appearance.toStored(state: String): RemoteAppearance =
            RemoteAppearance(
                theme = theme,
                layout = layout,
                density = density,
                paneAlpha = paneAlpha?.toString(),
                accountCorner = accountCorner,
                listAvatars = listAvatars,
                previewLines = previewLines,
                unreadEmphasis = unreadEmphasis,
                fontFamily = fontFamily,
                fontScale = fontScale,
                sidebarDensity = sidebarDensity,
                listDensity = listDensity,
                readingDensity = readingDensity,
                state = state.takeIf { it.isNotBlank() },
            )
    }
}

/**
 * This device's per-surface answer if it has one, otherwise the server's.
 *
 * Written out because `?:` cannot express it. A [DensityOverride] holding a null `wire` is a
 * deliberate "follow the global density", and `local?.wire ?: remote` reads that as "no answer" and
 * hands back the very override the user just cleared. The receiver being nullable and the payload
 * being nullable are two different questions and this is the function that keeps them apart.
 */
private fun DensityOverride?.orRemote(remote: String?): String? = if (this != null) wire else remote

/**
 * The server's copy with this device's unconfirmed changes on top.
 *
 * Pure and internal so the precedence rule is testable without a store, a client or a coroutine —
 * it is one line of policy and the whole feature rests on it.
 */
internal fun resolve(local: StoredAppearance, remote: RemoteAppearance): AppearanceSettings =
    AppearanceSettings(
        theme = local.theme ?: remote.theme,
        layout = local.layout ?: remote.layout,
        density = local.density ?: remote.density,
        paneAlpha = local.paneAlpha ?: remote.paneAlpha,
        dynamicColor = local.dynamicColor,
        reduceTransparency = local.reduceTransparency,
        syncWithServer = local.syncWithServer,
        accountCorner = local.accountCorner ?: remote.accountCorner,
        listAvatars = local.listAvatars ?: remote.listAvatars,
        previewLines = local.previewLines ?: remote.previewLines,
        unreadEmphasis = local.unreadEmphasis ?: remote.unreadEmphasis,
        fontFamily = local.fontFamily ?: remote.fontFamily,
        fontScale = local.fontScale ?: remote.fontScale,
        // The three that cannot be written `?:`. See `orRemote`.
        sidebarDensity = local.sidebarDensity.orRemote(remote.sidebarDensity),
        listDensity = local.listDensity.orRemote(remote.listDensity),
        readingDensity = local.readingDensity.orRemote(remote.readingDensity),
    )
