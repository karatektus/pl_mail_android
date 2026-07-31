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
