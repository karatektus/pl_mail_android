package de.plmail.core.data

import de.plmail.core.datastore.AppearanceStore
import de.plmail.core.datastore.DensityOverride
import de.plmail.core.datastore.RemoteAppearance
import de.plmail.core.datastore.StoredAppearance
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.methods.Appearance
import de.plmail.jmap.methods.AppearanceGet
import de.plmail.jmap.methods.AppearancePatch
import de.plmail.jmap.methods.AppearanceSet
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.Session
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * The user's appearance, kept in step with the server.
 *
 * **The server is the source and this device holds overrides.** A local choice is written to
 * DataStore immediately — so the app re-themes under the finger with no round trip — and pushed
 * with `Appearance/set`. Once the server confirms it, the local override is dropped and the
 * server's copy is what the app reads from then on. That is what makes a theme changed in the
 * browser show up here at all; a permanent local value would win forever and the sync would be
 * decorative.
 *
 * **There is no `Appearance/changes` and no push**, so remote changes are picked up on sync and on
 * foreground, and never polled for. A theme somebody changed on their laptop appears on the phone
 * the next time the phone looks, which for this product is the next time it is unlocked.
 *
 * **Only the properties the user touched are ever written.** Not an optimisation: the server holds
 * seven themes, background images, ink overrides and a blur this app cannot render, and a client
 * that sent a whole object back would flatten every one of them to whatever it had resolved. It is
 * also the entire mechanism by which `paper` survives — the app draws it as Light and has no way to
 * spell it, so the only write that can replace it is the user explicitly choosing a theme here.
 *
 * **Clamps are applied from the answer, never from the request.** `Appearance/set` reports what it
 * decided differently in `updated` — a slider pulled into range, a knob preset seeded by a change
 * of layout — and that is what gets stored.
 *
 * **Every one of those paragraphs is conditional on [setSyncWithServer].** With the sync off this
 * class stops talking to the server in both directions: [refresh] returns before it reads and
 * [flush] returns before it writes, so `Appearance/set` is never called and the browser's
 * appearance is left exactly as its owner last had it. Nothing else changes shape — the frozen
 * `RemoteAppearance` stays the base and local choices sit on top of it as ordinary overrides —
 * which is why an off switch needs two early returns rather than a second code path. The overrides
 * simply stop being *pending* and become what this phone looks like.
 */
@Singleton
class AppearanceRepository
@Inject
constructor(private val store: AppearanceStore, private val clients: AccountClients) {

    /**
     * One write at a time.
     *
     * Not about corruption — DataStore serialises its own edits — but about `ifInState`. Two
     * concurrent writes read the same state token and the second is refused as a `stateMismatch`
     * for no reason but their overlap, and the appearance screen makes concurrent writes easy: a
     * theme and a density are two taps a second apart.
     */
    private val writing = Mutex()

    /**
     * What the app should look like: the server's copy with this device's pending changes on top.
     */
    val settings: Flow<AppearanceSettings> =
        combine(store.appearance, store.remote) { local, remote -> resolve(local, remote) }
            .distinctUntilChanged()

    suspend fun setTheme(wire: String) = choose { store.setTheme(wire) }

    suspend fun setLayout(wire: String) = choose { store.setLayout(wire) }

    suspend fun setDensity(wire: String) = choose { store.setDensity(wire) }

    suspend fun setPaneAlpha(alpha: Float) = choose { store.setPaneAlpha(alpha) }

    suspend fun setAccountCorner(shown: Boolean) = choose { store.setAccountCorner(shown) }

    suspend fun setListAvatars(shown: Boolean) = choose { store.setListAvatars(shown) }

    suspend fun setPreviewLines(lines: Int) = choose { store.setPreviewLines(lines) }

    suspend fun setUnreadEmphasis(wire: String) = choose { store.setUnreadEmphasis(wire) }

    suspend fun setFontFamily(wire: String) = choose { store.setFontFamily(wire) }

    suspend fun setFontScale(scale: Float) = choose { store.setFontScale(scale) }

    suspend fun setSidebarDensity(override: DensityOverride) = choose {
        store.setSidebarDensity(override)
    }

    suspend fun setListDensity(override: DensityOverride) = choose {
        store.setListDensity(override)
    }

    suspend fun setReadingDensity(override: DensityOverride) = choose {
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
     * Reads the server's appearance and pushes anything this device still owes it.
     *
     * Called on sync and on foreground. Failures are swallowed: nothing here is an operation the
     * user asked for, an unreachable server leaves the local overrides pending exactly as an
     * offline change does, and a throw from a sync scope would take the sync down over a theme.
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

            flush()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // See the docblock. A theme is not worth failing a sync over, and
            // the pending overrides are still on disk for the next attempt.
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
     * Sends whatever this device has changed and the server has not confirmed.
     *
     * Retried once on `stateMismatch`, and the retry is a re-read rather than a blind resend: the
     * appearance changed under us — somebody moved a slider in a browser tab — so the fresh state
     * is fetched, the fresh values are stored, and the user's own pending change is re-applied on
     * top of them. Their tap is the newer intent; everything they did not touch takes the browser's
     * new value. A second failure leaves the override pending for the next sync, which is the same
     * place an offline change waits.
     */
    private suspend fun flush(): Unit = writing.withLock { attemptFlush(mayRetry = true) }

    private suspend fun attemptFlush(mayRetry: Boolean) {
        val client = clients.current() ?: return
        val local = store.appearance.first()

        // The write half of the switch, and the assertion the test in
        // `AppearanceRepositoryTest` is really making: with the sync off, no
        // `Appearance/set` leaves the device at all. Checked here rather than
        // only in the two callers because this is the one function that builds a
        // patch, and a third caller added later would otherwise silently reopen
        // the hole.
        if (!local.syncWithServer) return
        if (!local.hasPendingWrites) return

        val remote = store.remote.first()

        val patch = AppearancePatch.build {
            local.theme?.let { theme(it) }
            local.layout?.let { layout(it) }
            local.density?.let { density(it) }
            local.paneAlpha?.toFloatOrNull()?.let { paneAlpha(it) }
            local.accountCorner?.let { accountCorner(it) }
            local.listAvatars?.let { listAvatars(it) }
            local.previewLines?.let { previewLines(it) }
            local.unreadEmphasis?.let { unreadEmphasis(it) }
            local.fontFamily?.let { fontFamily(it) }
            local.fontScale?.let { fontScale(it) }
            // `.wire`, not the override itself: the outer null is "this device
            // has not touched the surface" and never reaches the wire, while the
            // inner one is the instruction that clears the override and must be
            // sent as an explicit JSON null. Collapsing them would make "Follow
            // the overall density" a control that writes nothing.
            local.sidebarDensity?.let { sidebarDensity(it.wire) }
            local.listDensity?.let { listDensity(it.wire) }
            local.readingDensity?.let { readingDensity(it.wire) }
        }

        if (patch.isEmpty) return

        val request = RequestBuilder(using = Capability.USING_APPEARANCE)
        val set = request.add(AppearanceSet(patch, ifInState = remote.state))

        val result =
            try {
                client.send(request).result(set)
            } catch (mismatch: JmapError.MethodFailed) {
                if (mismatch.type != STATE_MISMATCH || !mayRetry) throw mismatch

                reread(client)
                return attemptFlush(mayRetry = false)
            }

        // Refused rather than mismatched: a value this build believes in and
        // this server does not. Dropping the override is the only way out --
        // keeping it would re-send the same refused patch on every sync forever
        // -- and the next refresh puts the server's own value back on screen,
        // which is the honest answer to "that theme does not exist here".
        if (result.refusal != null) {
            store.clearOverrides(patch.properties)
            return
        }

        // What was sent, with what the server decided differently laid over the
        // top: a clamped slider, or the knob preset a change of layout seeds.
        val applied =
            Appearance(
                    theme = local.theme ?: remote.theme,
                    layout = local.layout ?: remote.layout,
                    density = local.density ?: remote.density,
                    paneAlpha = (local.paneAlpha ?: remote.paneAlpha)?.toFloatOrNull(),
                    accountCorner = local.accountCorner ?: remote.accountCorner,
                    listAvatars = local.listAvatars ?: remote.listAvatars,
                    previewLines = local.previewLines ?: remote.previewLines,
                    unreadEmphasis = local.unreadEmphasis ?: remote.unreadEmphasis,
                    fontFamily = local.fontFamily ?: remote.fontFamily,
                    fontScale = local.fontScale ?: remote.fontScale,
                    // The same three-state read as in `resolve`, and the same bug
                    // avoided: an override that says "follow" has a null `wire`,
                    // so `?:` here would fall through to the remote value the
                    // user has just cleared and store it back as though nothing
                    // had happened.
                    sidebarDensity = local.sidebarDensity.orRemote(remote.sidebarDensity),
                    listDensity = local.listDensity.orRemote(remote.listDensity),
                    readingDensity = local.readingDensity.orRemote(remote.readingDensity),
                )
                .with(result.reported)

        store.setRemote(applied.toStored(result.newState))
        store.clearOverrides(patch.properties)
    }

    private suspend fun reread(client: JmapClient) {
        val request = RequestBuilder(using = Capability.USING_APPEARANCE)
        val get = request.add(AppearanceGet())
        val result = client.send(request).result(get)

        result.appearance?.let { store.setRemote(it.toStored(result.state)) }
    }

    /**
     * Writes the choice, then tries to send it.
     *
     * In that order and never the other way round: the screen is its own preview, so the store has
     * to change before the finger lifts. The send is what may fail, and a failure leaves a pending
     * override rather than an error — which is also exactly what happens with no network at all.
     */
    private suspend fun choose(write: suspend () -> Unit) {
        write()

        try {
            flush()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Pending on disk, retried on the next sync.
        }
    }

    private companion object {
        const val STATE_MISMATCH = "stateMismatch"

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
