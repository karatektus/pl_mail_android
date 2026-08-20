package de.plmail.feature.mail.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tap is actually connected to the rule.
 *
 * `LinkToOpenTest` covers *which* links should leave the app, and it would have passed happily
 * throughout the months when every link in every message did nothing at all — because the fault was
 * not in the rule, it was that `shouldOverrideUrlLoading` returned `true` and told nobody. A
 * docblock said the reader handled the tap. No code did.
 *
 * So this pins the wiring rather than the policy: that the client reports an openable link, that it
 * reports nothing for one the rule rejects, and that it never lets the WebView navigate in place
 * either way — the message must not be replaced by a web page in a view with no address bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageClientLinkTest {

    // A real WebView, because the signature demands one. It is never asked to
    // do anything -- the whole point of the override is that it does not load.
    private val webView by lazy {
        WebView(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a tapped web link is reported outward`() {
        val opened = mutableListOf<Uri>()
        val client = MessageClient(RemoteImages.BLOCKED, { null }, onLink = { opened += it })

        val handled =
            client.shouldOverrideUrlLoading(
                view = webView,
                request = request("https://example.org/a", hasGesture = true),
            )

        assertEquals(listOf(Uri.parse("https://example.org/a")), opened)
        assertTrue(handled, "the WebView must never follow the link itself")
    }

    @Test
    fun `a link the rule rejects is reported to nobody, and still not followed`() {
        val opened = mutableListOf<Uri>()
        val client = MessageClient(RemoteImages.BLOCKED, { null }, onLink = { opened += it })

        val handled =
            client.shouldOverrideUrlLoading(
                view = webView,
                request = request("intent://evil#Intent;end", hasGesture = true),
            )

        assertEquals(emptyList(), opened)
        assertTrue(handled, "refusing to open it must not mean loading it instead")
    }

    /** A redirect on load is not a tap. See `linkToOpen`. */
    @Test
    fun `a navigation with no gesture opens nothing`() {
        val opened = mutableListOf<Uri>()
        val client = MessageClient(RemoteImages.BLOCKED, { null }, onLink = { opened += it })

        client.shouldOverrideUrlLoading(
            view = webView,
            request = request("https://tracker.example", hasGesture = false),
        )

        assertEquals(emptyList(), opened)
    }

    private fun request(url: String, hasGesture: Boolean): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = true

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = hasGesture

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }
}
