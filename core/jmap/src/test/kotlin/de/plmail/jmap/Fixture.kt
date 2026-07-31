package de.plmail.jmap

import kotlinx.serialization.json.Json

/**
 * Loads the captured wire fixtures.
 *
 * These files came off a real server verbatim; see the README beside them for what each one pins
 * down and, just as importantly, what the current seed cannot exercise.
 */
object Fixture {

    /**
     * Lenient in exactly the way the real client is.
     *
     * `ignoreUnknownKeys` is not laziness: the server adds fields — plMail's `labelId` and
     * `snoozedUntil` are already two — and a client that refuses to parse a response containing a
     * property it has not heard of breaks on every server upgrade. `explicitNulls = false` because
     * JMAP omits and nulls interchangeably.
     */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun read(name: String): String =
        requireNotNull(Fixture::class.java.getResourceAsStream("/jmap/$name")) {
                "No fixture /jmap/$name — capture it from the test server, do not hand-write it."
            }
            .use { it.readBytes().decodeToString() }

    inline fun <reified T> decode(name: String): T = json.decodeFromString(read(name))
}
