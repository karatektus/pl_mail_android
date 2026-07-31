package de.plmail.feature.mail.reader

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Whether a message may load pictures from the internet.
 *
 * Off by default, and this is a privacy decision rather than a bandwidth one: a remote image in
 * mail is a tracking pixel far more often than it is a photograph, and loading it tells the sender
 * the message was opened, roughly when, and from which network.
 */
enum class RemoteImages {
    BLOCKED,
    ALLOWED,
}

/**
 * One message body, in its own WebView.
 *
 * Per message rather than per thread: each needs its own [MessageRenderStyle], and a single WebView
 * holding a concatenated thread would have to pick one strategy for a plain reply and the marketing
 * mail it quotes.
 *
 * The sandbox is the point. JavaScript is off, file and content access are off, and nothing but the
 * message's own markup is ever loaded — which is what makes it safe to embed the body unescaped.
 * The server sanitises the HTML; this stops whatever survives that from reaching anything.
 */
@Composable
fun MessageWebView(
    body: String,
    style: MessageRenderStyle,
    remoteImages: RemoteImages,
    modifier: Modifier = Modifier,
    /** Resolves an inline `cid:` reference to bytes already in the blob cache. */
    inlineImage: (String) -> InlineImage? = { null },
) {
    val document = remember(body, style) { MessageDocument.wrap(body, style) }

    AndroidView(
        // clipToBounds because a WebView paints its own background across the
        // area it has drawn, which in a LazyColumn is larger than the bounds it
        // was laid out with -- it covers the sender row above it, which then
        // exists in the accessibility tree while being invisible on screen.
        modifier = modifier.fillMaxWidth().clipToBounds(),
        factory = { context ->
            WebView(context).apply {
                configure()

                // Transparent, so the Compose surface behind shows through
                // instead of a white rectangle in a dark theme. The document's
                // own CSS paints the background it wants.
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // WRAP_CONTENT, or the WebView reports its default height inside
                // the LazyColumn's unbounded measurement and pushes everything
                // above it off the screen. The symptom is a reader showing only
                // a body, with the subject and sender apparently gone.
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        },
        update = { webView ->
            webView.webViewClient = MessageClient(remoteImages, inlineImage)

            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                // Only where the message declared its own dark mode. Letting
                // WebView darken algorithmically anywhere else would fight the
                // CSS in MessageDocument and produce a third result neither of
                // them intended.
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                    webView.settings,
                    style == MessageRenderStyle.DARK_NATIVE,
                )
            }

            // A null base URL, so the document is a unique opaque origin.
            // Passing the server's URL instead would put the message in the
            // same origin as the mailbox it came from.
            webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
        },
    )
}

/** An inline part, already fetched. */
data class InlineImage(val bytes: ByteArray, val mimeType: String) {
    // data class + ByteArray: the generated equals compares by identity, which
    // is never what a caller means.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is InlineImage && mimeType == other.mimeType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

/**
 * Decides what a message is allowed to fetch.
 *
 * Default-deny: anything not explicitly recognised is refused, so a scheme nobody thought about
 * fails closed rather than reaching the network.
 */
private class MessageClient(
    private val remoteImages: RemoteImages,
    private val inlineImage: (String) -> InlineImage?,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url

        return when {
            // Served from the blob cache rather than the network: a cid: part
            // belongs to the message and was already downloaded with it.
            url.scheme.equals("cid", ignoreCase = true) ->
                inlineImage(url.schemeSpecificPart)?.let {
                    WebResourceResponse(it.mimeType, null, ByteArrayInputStream(it.bytes))
                } ?: BLOCKED

            url.scheme.equals("data", ignoreCase = true) -> null

            remoteImages == RemoteImages.ALLOWED &&
                (url.scheme.equals("https", ignoreCase = true) ||
                    url.scheme.equals("http", ignoreCase = true)) -> null

            else -> BLOCKED
        }
    }

    /**
     * Nothing navigates inside the reader.
     *
     * A tapped link is handled by the reader itself, which can show where it goes before opening
     * it. Letting the WebView follow it would replace the message with a web page inside a view
     * that has no address bar and no way back.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        true

    private companion object {
        /**
         * An empty 200 rather than null.
         *
         * Returning null means "load it normally", which is the opposite of blocking — the mistake
         * that turns a privacy control into a no-op.
         */
        val BLOCKED: WebResourceResponse
            get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}

/**
 * The sandbox.
 *
 * Suppressed lint: `setJavaScriptEnabled(false)` is the safe direction and the check only fires on
 * the setter existing at all.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure() {
    settings.javaScriptEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.domStorageEnabled = false
    settings.setGeolocationEnabled(false)
    // The message is already width-constrained by MessageDocument's CSS; this
    // stops a table with fixed pixel widths from making the whole view
    // horizontally scrollable anyway.
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = false
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
}
