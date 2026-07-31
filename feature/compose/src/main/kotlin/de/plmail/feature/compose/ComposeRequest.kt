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
}
