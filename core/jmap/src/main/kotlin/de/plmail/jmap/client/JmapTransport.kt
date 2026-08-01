package de.plmail.jmap.client

/**
 * The seam between the protocol and whatever actually moves bytes.
 *
 * Deliberately tiny, and deliberately free of any HTTP library's types. That is what lets the
 * entire protocol layer be tested against a fake that returns canned or generated responses, on the
 * JVM, with no server and no emulator — and it is why OkHttp is an `implementation` dependency of
 * this module rather than an `api` one.
 */
fun interface JmapTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/**
 * A transport that can deliver a response body as it arrives.
 *
 * Separate from [JmapTransport] rather than a method on it, because only one caller needs it and
 * every fake would otherwise have to implement a streaming method it never uses.
 *
 * The distinction is not academic. `send` buffers the whole body, and an EventSource connection is
 * held open for 300 seconds by design — served through `send`, "live" updates would all arrive at
 * once, five minutes late, while appearing to work.
 */
interface StreamingTransport : JmapTransport {
    /** Emits body lines as the server writes them. Completes when it closes. */
    fun lines(request: HttpRequest): kotlinx.coroutines.flow.Flow<String>
}

/**
 * A transport that can hand a response body over without buffering it first.
 *
 * Also separate from [JmapTransport], and for a harder reason than [StreamingTransport]'s. `send`
 * returns a `ByteArray`, and the session advertises a fifty-megabyte ceiling on what a message may
 * carry — so downloading an attachment through `send` allocates the whole file on the heap of a
 * phone that is also holding a WebView. It works for every attachment anybody tests with and fails
 * on the one somebody actually needed.
 */
interface DownloadingTransport : JmapTransport {
    /**
     * Runs [receive] with the body still open, and closes it afterwards however [receive] ends.
     *
     * The body is deliberately not returned: an `InputStream` that outlives the call is a
     * connection nobody closes, and OkHttp's pool would be exhausted by four abandoned downloads.
     */
    suspend fun <T> download(request: HttpRequest, receive: suspend (ResponseBody) -> T): T
}

/** One response, still being read. Valid only inside [DownloadingTransport.download]. */
class ResponseBody(
    val status: Int,
    val contentType: String?,
    /** What the server said, or null when it used chunked encoding and did not say. */
    val length: Long?,
    val bytes: java.io.InputStream,
) {
    val isSuccess: Boolean
        get() = status in 200..299
}

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    // data class with a ByteArray: the generated equals/hashCode compare the
    // array by identity, which is never what a caller means. Overridden rather
    // than left as a trap for whoever first puts one of these in a set.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false

        return url == other.url &&
            method == other.method &&
            headers == other.headers &&
            body.contentEqualsOrBothNull(other.body)
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val isSuccess: Boolean
        get() = status in 200..299

    fun bodyAsText(): String = body.decodeToString()

    /** Case-insensitive, because header names on the wire are. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false

        return status == other.status && headers == other.headers && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }
