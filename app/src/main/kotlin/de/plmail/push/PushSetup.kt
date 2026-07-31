package de.plmail.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Turning push on, and being honest when it cannot be.
 *
 * UnifiedPush needs a *distributor* installed — a separate app that owns the endpoint URL and holds
 * the connection, because RFC 8030 requires a push service and a native app is not one. There may
 * be none, and that is an ordinary state rather than an error: the periodic sync never stopped, so
 * the consequence is slower mail rather than no mail.
 */
object PushSetup {

    /** One instance per app; a second would register a second endpoint for the same mailbox. */
    private const val INSTANCE = "plmail"

    /**
     * Whether anything on this device can deliver a push.
     *
     * Requires the `<queries>` block in the manifest to answer truthfully. Without it Android hides
     * every distributor from the app and this returns false on a device where one is installed.
     */
    fun isAvailable(context: Context): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /**
     * Registers with a distributor, if one is available.
     *
     * Picks the saved distributor when there is one and the only installed distributor otherwise.
     * With several installed the choice belongs to the user — a settings screen that does not exist
     * yet — and registering with an arbitrary one would silently pick for them.
     */
    fun enable(context: Context): Boolean {
        val distributors = UnifiedPush.getDistributors(context)

        val chosen =
            UnifiedPush.getSavedDistributor(context) ?: distributors.singleOrNull() ?: return false

        UnifiedPush.saveDistributor(context, chosen)

        // The endpoint arrives asynchronously in PlMailPushReceiver, which
        // registers it with the server. Nothing is live until the verification
        // push that follows has been answered.
        UnifiedPush.register(context, INSTANCE)

        return true
    }

    /** Stops push, leaving the periodic sync as the only path. */
    fun disable(context: Context) {
        UnifiedPush.unregister(context, INSTANCE)
    }
}
