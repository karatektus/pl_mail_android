package de.plmail.feature.compose

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * A message another app has handed to plMail, exactly as it arrived.
 *
 * Addresses are held as **text** rather than as `EmailAddress`, and that is reuse rather than
 * laziness: `Name <address>` and bare addresses and comma-or-semicolon separated lists all arrive
 * here, and [parseAddresses] already knows every one of those shapes because it is what the
 * recipient field parses pasted text with. One parser, one set of rules, one place where an address
 * can be silently dropped.
 *
 * [streams] are content URIs still standing on the temporary grant the intent came with — see
 * [SharedAttachmentStore] for why nothing may keep them in that form.
 */
data class SharedMessage(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    /** Plain text, as the other app wrote it. Escaped into the body when the composer opens. */
    val text: String = "",
    /** Content URIs, as strings, on a grant that outlives neither this task nor the process. */
    val streams: List<String> = emptyList(),
)

/**
 * The four intents that mean "write a mail", read into one shape.
 *
 * `SEND` and `SEND_MULTIPLE` are the share sheet. `SENDTO` is another app asking for a composer on
 * a known address — a contacts entry, a "contact us" button. `VIEW` on a `mailto:` is a link tapped
 * in a browser or a message. They differ in where the recipients live and in nothing else, so they
 * converge here rather than in four branches of the activity.
 *
 * **Both halves are read for `SENDTO` and `VIEW`.** A `mailto:` intent may also carry
 * `EXTRA_SUBJECT` and `EXTRA_TEXT`, and several address-book apps send exactly that. The URI wins
 * where it said something, because it is the more specific of the two and the one the user actually
 * tapped; the extras fill what it left blank.
 */
object ShareIntake {

    /** What [intent] asks the composer to open on, or null when it asks for something else. */
    fun read(intent: Intent?): SharedMessage? =
        when (intent?.action) {
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE -> intent.extras()
            // Null when the data is not a `mailto:`, which is how the pairing
            // deep link keeps arriving at the code that handles pairing: both
            // filters are VIEW on the same activity, and the scheme is the only
            // thing that tells them apart once the intent is in hand.
            Intent.ACTION_SENDTO,
            Intent.ACTION_VIEW ->
                Mailto.parse(intent.data?.toString())?.filledInFrom(intent.extras())
            else -> null
        }
}

/** The URI's answer, with the extras supplying whatever it did not mention. */
private fun SharedMessage.filledInFrom(extras: SharedMessage): SharedMessage =
    SharedMessage(
        to = to + extras.to,
        cc = cc + extras.cc,
        bcc = bcc + extras.bcc,
        subject = subject.ifBlank { extras.subject },
        text = text.ifBlank { extras.text },
        streams = extras.streams,
    )

private fun Intent.extras(): SharedMessage =
    SharedMessage(
        to = addresses(Intent.EXTRA_EMAIL),
        cc = addresses(Intent.EXTRA_CC),
        bcc = addresses(Intent.EXTRA_BCC),
        subject = subject(),
        text = text(),
        streams = streams(),
    )

/**
 * `EXTRA_SUBJECT`, or the title when there is no subject.
 *
 * `getCharSequenceExtra` rather than `getStringExtra`, here and for the body: the platform
 * documents both as `String` and a good number of apps put a `Spanned` in anyway — a browser
 * sharing a selection, anything that built the text with `HtmlCompat`. `getStringExtra` returns
 * null for those, which is a subject line that silently does not arrive.
 *
 * `EXTRA_TITLE` as the fallback because that is what a share sheet shows as the label, and an app
 * that sets only the label has still told us what the thing is called.
 */
private fun Intent.subject(): String =
    (getCharSequenceExtra(Intent.EXTRA_SUBJECT) ?: getCharSequenceExtra(Intent.EXTRA_TITLE))
        ?.toString()
        ?.trim()
        .orEmpty()

/**
 * The shared text.
 *
 * **`EXTRA_HTML_TEXT` is deliberately not read**, though it is often there beside the plain text.
 * It is somebody else's markup, and the rule this module already keeps — see [ComposeViewModel]'s
 * note on the quoted original — is that foreign HTML never enters the editor, because
 * round-tripping it through the editor's parser reflows it into something the sender did not write.
 * The plain-text half of the same share says the same thing and can be trusted to.
 */
private fun Intent.text(): String {
    getCharSequenceExtra(Intent.EXTRA_TEXT)?.let {
        return it.toString()
    }

    // SEND_MULTIPLE puts a list here. A blank line between the parts rather
    // than a newline: they are separate things somebody selected, not one
    // paragraph that happens to have been split.
    getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
        ?.takeIf { it.isNotEmpty() }
        ?.let { parts ->
            return parts.joinToString(PART_BREAK) { it.toString() }
        }

    val clip = clipData ?: return ""

    return (0..<clip.itemCount)
        .mapNotNull { clip.getItemAt(it).text?.toString() }
        .filter { it.isNotBlank() }
        .joinToString(PART_BREAK)
}

/**
 * An address extra, in all three shapes it is sent in.
 *
 * `String[]` is what the platform documents. An `ArrayList<String>` is what anything built on
 * `ShareCompat` produces, and a bare `String` is what a hand-rolled intent with one recipient
 * usually carries. All three are common enough that picking one and calling it the contract means
 * losing recipients from real apps.
 */
private fun Intent.addresses(name: String): List<String> {
    getStringArrayExtra(name)
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            return it.toList()
        }

    getStringArrayListExtra(name)
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            return it.toList()
        }

    return getStringExtra(name)?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
}

/**
 * The files, from whichever of the two places they were put.
 *
 * `IntentCompat` rather than `getParcelableExtra` directly: the untyped overloads are deprecated
 * from API 33 and warnings are errors in this build, and the typed ones do not exist below it.
 *
 * `ClipData` last, and it is not redundant. `EXTRA_STREAM` is the extra the receiver reads;
 * `ClipData` is what carries the *grant*, and an app that set only the clip — which is legal, and
 * which some system components do — has still shared a file with us. Only consulted when the extra
 * said nothing, so a well-formed share is never counted twice.
 */
private fun Intent.streams(): List<String> {
    IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.let {
        return listOf(it.toString())
    }

    IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
        ?.takeIf { it.isNotEmpty() }
        ?.let { uris ->
            return uris.map { it.toString() }
        }

    val clip = clipData ?: return emptyList()

    return (0..<clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.toString() }
}

private const val PART_BREAK = "\n\n"
