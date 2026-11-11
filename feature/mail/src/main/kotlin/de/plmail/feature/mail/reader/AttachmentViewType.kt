package de.plmail.feature.mail.reader

import android.webkit.MimeTypeMap
import java.util.Locale

/**
 * The type an attachment is offered to other apps under.
 *
 * The sender's `Content-Type` is only a claim, and for files that come out of scanners, accounting
 * packages and plenty of mailers it is `application/octet-stream` whatever the file is. A PDF
 * reader registers for `application/pdf`, so an `ACTION_VIEW` carrying `octet-stream` matches none
 * of them and the "Open with" chooser comes up empty, even with a reader installed. A few senders
 * use an old alias for PDF (`application/x-pdf`), which readers do not list either.
 *
 * So a type that says nothing — blank or one of the generic binary types — and a known alias are
 * replaced by what the file's extension stands for, when the platform knows it. Anything more
 * specific than that is the sender's claim and is kept as it is: a file named `report.pdf` that
 * declares itself `image/png` is not for this function to second-guess.
 *
 * The result is only for the intent. What the server is asked for (the download's `type`) and what
 * is cached on disk are untouched.
 */
internal fun viewType(declared: String?, name: String?): String {
    val type = declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()

    val aliased = ALIASES[type]
    if (aliased != null) return aliased

    if (type.isNotEmpty() && type !in GENERIC) return declared.orEmpty().trim()

    val extension =
        name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase(Locale.ROOT)
    val fromExtension =
        extension
            ?.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    return fromExtension ?: type.ifEmpty { OCTET_STREAM }
}

private const val OCTET_STREAM = "application/octet-stream"

/** Types that carry no information about the file. */
private val GENERIC = setOf(OCTET_STREAM, "binary/octet-stream", "application/x-download")

/** Old spellings of types the platform and its apps know under one name. */
private val ALIASES =
    mapOf("application/x-pdf" to "application/pdf", "application/acrobat" to "application/pdf")
