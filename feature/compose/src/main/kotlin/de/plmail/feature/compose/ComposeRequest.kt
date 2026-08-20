package de.plmail.feature.compose

/**
 * What the composer was opened to do.
 *
 * Every case carries primitives only, so the whole request survives a `rememberSaveable` and a
 * rotation reopens the same composer rather than an empty one. A draft object here would not:
 * everything else the composer holds is either recoverable from the server or, in the case of
 * unsaved keystrokes, gone the moment the process is — which is the deal autosave makes.
 */
sealed interface ComposeRequest {

    /** A blank message. */
    data object New : ComposeRequest

    data class Reply(
        val accountKey: String,
        val emailId: String,
        /** Reply-all. Copies everyone the original reached, minus the user. */
        val all: Boolean,
    ) : ComposeRequest

    data class Forward(val accountKey: String, val emailId: String) : ComposeRequest

    /**
     * An existing draft, by id.
     *
     * How the composer reopens after an undone or failed send — the mail is already in Drafts by
     * then, so reopening loads it rather than restoring an in-memory copy that might disagree with
     * it.
     */
    data class Edit(val accountKey: String, val emailId: String) : ComposeRequest

    /**
     * A message another app handed over: a share, a `mailto:` link, a "contact us" button.
     *
     * No `accountKey`, unlike every case above. There is no account in the intent to carry one —
     * the sharing app has no idea plMail has accounts — so the composer opens on the first identity
     * the same way [New] does, and the user changes it from the From row if it is the wrong one.
     *
     * [attachments] are **directories under this app's own cache**, not the content URIs the intent
     * arrived with. That is the difference between a draft that survives an hour and one that does
     * not, and [SharedAttachmentStore] is where the reasoning lives. It also keeps the primitives
     * promise above literally true across a process death: what comes back out of the bundle is a
     * path to a file that is still there.
     *
     * [tooLarge] and [unreadable] are the files that did not make it, by name, so the composer can
     * say which and why instead of opening one attachment short of what was shared.
     */
    data class Share(
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String = "",
        /** Plain text. Escaped into the body when the composer opens; never inserted as markup. */
        val text: String = "",
        val attachments: List<String> = emptyList(),
        val tooLarge: List<String> = emptyList(),
        val unreadable: List<String> = emptyList(),
    ) : ComposeRequest
}
