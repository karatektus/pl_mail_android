package de.plmail.feature.mail.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

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
    palette: MessagePalette,
    remoteImages: RemoteImages,
    modifier: Modifier = Modifier,
    /** Resolves an inline `cid:` reference to bytes already in the blob cache. */
    inlineImage: (String) -> InlineImage? = { null },
    /**
     * A link the user tapped, already filtered down to one worth leaving the app for.
     *
     * The reader opens it; this view deliberately does not, because "what happens to a link" is a
     * decision about the app rather than about rendering — and a composable that reached for a
     * `Context` and fired an intent could not be rendered in a test at all.
     */
    onLink: (Uri) -> Unit = {},
) {
    // Keyed on `remoteImages` as well, because the document itself now differs
    // between the two states -- blocked pictures are rewritten into placeholders
    // inside it. Without the key, allowing pictures would swap the client out
    // and leave the placeholders on screen.
    val document =
        remember(body, style, palette, remoteImages) {
            MessageDocument.wrap(body, style, palette, remoteImages)
        }

    /**
     * The document's height, in pixels, as the WebView last reported it.
     *
     * This is the fix for "you cannot scroll past the message", and it has to be Compose state
     * rather than a `WRAP_CONTENT` layout param. A WebView's content height is not known when the
     * view is first measured — the page has not been laid out yet — so the interop node is measured
     * at whatever the view says then, and when the page finishes and the view asks for another
     * layout, only the *view* resizes. The Compose `LayoutNode` keeps the height it was given, so
     * the `LazyColumn` computes a scroll range for an item that is a fraction of what is actually
     * drawn, decides there is nothing to scroll, and the reader is frozen with the message's own
     * chrome — and every action under it — off the bottom of the screen.
     *
     * Reset with the document, so toggling "show original" on a message that gets shorter does not
     * leave the item padded out to the taller rendering's height.
     */
    var contentHeight by remember(document) { mutableIntStateOf(0) }

    AndroidView(
        modifier =
            modifier
                .fillMaxWidth()
                // Only once a height has been reported. Before that the view keeps
                // WRAP_CONTENT and draws whatever it can, which is what makes the
                // first frame of a cached message immediate rather than empty.
                .then(
                    if (contentHeight > 0) {
                        Modifier.height(with(LocalDensity.current) { contentHeight.toDp() })
                    } else {
                        Modifier
                    }
                )
                // clipToBounds because a WebView paints its own background across
                // the area it has drawn, which in a LazyColumn is larger than the
                // bounds it was laid out with -- it covers the sender row above it,
                // which then exists in the accessibility tree while being invisible
                // on screen.
                .clipToBounds(),
        factory = { context ->
            ReaderWebView(context).apply {
                configure()

                // Transparent, so the Compose surface behind shows through
                // instead of a white rectangle in a dark theme. The document's
                // own CSS paints the background it wants.
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // WRAP_CONTENT until the height above is known, or the WebView
                // reports its default height inside the LazyColumn's unbounded
                // measurement and pushes everything above it off the screen. The
                // symptom is a reader showing only a body, with the subject and
                // sender apparently gone.
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        },
        update = { webView ->
            webView.onContentHeight = { height -> contentHeight = height }

            webView.webViewClient = MessageClient(remoteImages, inlineImage, onLink)

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

/**
 * A WebView that reports its height to Compose, and gives a vertical drag back to the list.
 *
 * The two together are "you cannot scroll past the message", and the *order* is worth recording
 * because it was diagnosed the wrong way round first. The height is the cause: with the interop
 * node measured short, the `LazyColumn` has no scroll range at all, and a drag started anywhere —
 * including on the sender row, well outside this view — moves nothing. The gesture work below only
 * matters once there is something to scroll; its absence is what made the reader creep down by a
 * couple of pixels per swipe rather than scroll, because the WebView was taking the drag and
 * spending it on a few pixels of overflow of its own.
 *
 * `android.webkit.WebView` is not a `NestedScrollingChild` — it never has been — so Compose's
 * nested-scroll interop has nothing on the other end to talk to, and the interop view takes the
 * gesture on ACTION_DOWN and keeps it. Nothing here tries to change that. The gesture is
 * disambiguated once, at the touch slop, and then owned: a drag that is mostly vertical releases
 * the ancestors' interception, which is the signal Compose's `PointerInteropFilter` watches for and
 * which lets the list's own scroll gesture take the pointer over. Anything else stays with the
 * WebView — a tap, a long press for text selection, and in particular a horizontal drag, which is
 * how a table too wide to reflow is read (see [MessageDocument]).
 *
 * The release is re-asserted *after* `super.onTouchEvent`, not before, because the WebView asks for
 * interception itself while it believes it is scrolling.
 */
private class ReaderWebView(context: Context) : WebView(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** Null until the gesture has moved past the slop and its direction is known. */
    private var isVerticalDrag: Boolean? = null

    /** Told the composable how tall the document is. See [MessageWebView]. */
    var onContentHeight: (Int) -> Unit = {}

    private var reportedHeight = 0

    /**
     * False until *this* class's constructor has run, and it is load-bearing rather than defensive.
     *
     * `WebView`'s inherited constructor calls `requestLayout()` — `ViewGroup.initViewGroup` does it
     * through `setFlags` — so the override below runs before the Chromium backend behind this view
     * exists. Asking it for a scroll range at that point throws `IllegalStateException: AwContents
     * must be created if we are not posting!` and the app dies the moment a message is opened.
     * Found by running it; nothing in the type system hints at it, because a Kotlin subclass's
     * fields are all still at their JVM defaults while the superclass constructor is on the stack —
     * which is exactly what makes this flag work.
     */
    private var isConstructed = false

    init {
        isConstructed = true
    }

    /**
     * The one moment the document's height is known to have changed.
     *
     * WebView raises this itself when its content size changes — it is what a `WRAP_CONTENT`
     * WebView in a plain `ScrollView` relies on — so it is the honest signal rather than a poll on
     * a timer.
     *
     * `contentHeight`, deliberately, and **not** `computeVerticalScrollRange`, which was the first
     * attempt and looked better for being already in pixels. A scroll range is `max(content, the
     * view's own height)`, so once this view has been laid out at some height it can never report
     * anything smaller — the reader showed the receipt followed by a screen and a half of empty
     * card, because the first, tall measurement latched. A document's height has to be able to go
     * down as well as up.
     *
     * CSS pixels are converted with the display density rather than with `getScale()`: the scale is
     * fixed here — the document declares `initial-scale=1` and zoom is off — and `getScale()` is
     * deprecated.
     *
     * Guarded on the value rather than fired every time, because Compose reacts by re-measuring
     * this view, which requests layout again — an unguarded report is an infinite loop rather than
     * a taller message.
     */
    override fun requestLayout() {
        super.requestLayout()

        if (!isConstructed) return

        val height = (contentHeight * resources.displayMetrics.density).roundToInt()

        if (height > 0 && height != reportedHeight) {
            reportedHeight = height
            onContentHeight(height)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isVerticalDrag = null

                // Claimed up front and given back below if the drag turns out to
                // be the list's. A tap has to reach the WebView intact or links
                // and text selection stop working.
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE ->
                if (isVerticalDrag == null) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)

                    if (dx > slop || dy > slop) isVerticalDrag = dy > dx
                }
        }

        val handled = super.onTouchEvent(event)

        if (isVerticalDrag == true) parent?.requestDisallowInterceptTouchEvent(false)

        return handled
    }
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
// The handler this asks for is `onRenderProcessGone` below, and it is a real
// one: it detaches the view, destroys it and returns true. The androidx.webkit
// check does not recognise the Kotlin override -- it reports the class and its
// supertype rather than a missing member -- so this is a checker limitation
// rather than a waiver. If a future webkit release starts seeing it, delete
// this line; if anybody deletes the override, put the suppression back only
// after putting the override back.
@Suppress("MissingOnRenderProcessGone")
internal class MessageClient(
    private val remoteImages: RemoteImages,
    private val inlineImage: (String) -> InlineImage?,
    private val onLink: (Uri) -> Unit,
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
     * Nothing navigates inside the reader, and a tapped link leaves the app.
     *
     * Always true: the WebView never follows a link itself, because that would replace the message
     * with a web page in a view with no address bar and no way back. What it did *instead*, until
     * this was fixed, was nothing at all — the docblock here claimed the reader handled the tap and
     * no code anywhere did. Every link in every message was inert, which is what a user reported.
     *
     * See [linkToOpen] for which links leave and which are dropped.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        linkToOpen(request.url, request.hasGesture(), request.isForMainFrame)?.let(onLink)

        return true
    }

    /**
     * The renderer died. Take the view down rather than the app.
     *
     * WebView runs the message in a **separate process**, and when that process is killed — the
     * system reclaiming memory, or the renderer falling over on the message itself — the default
     * behaviour is to raise the failure into this one. Returning false, which is what not
     * overriding this amounts to, crashes the app.
     *
     * That is not a hypothetical here. This view renders whatever HTML somebody was sent: a mail
     * with a ten-megapixel background, a table thousands of rows deep, a marketing template that
     * animates. On a phone under memory pressure the renderer is exactly what the system kills
     * first, and losing the whole app to a newsletter is the worst possible reading of "the message
     * could not be displayed".
     *
     * The view is detached and destroyed before returning, because a WebView whose renderer has
     * gone is unusable and holding one leaks the surface it was attached to. What the reader shows
     * afterwards is an empty message body — the header, the actions and the way back are all
     * outside this view and survive, which is the point.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()

        return true
    }

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

    // The viewport is the pane's own width in CSS pixels, and the message is
    // fitted to it by MessageDocument's stylesheet rather than by zooming.
    //
    // `loadWithOverviewMode` used to be on here with a comment claiming it
    // stopped fixed-width tables from scrolling the view. It does not: it only
    // applies when the wide viewport is enabled, so it was doing nothing at all
    // while reading as the thing that handled the case. Turning the pair on
    // instead is the other legitimate answer -- lay the message out at its
    // authored width and zoom the page out to fit -- and it was rejected
    // because an 840px receipt on a phone lands near half scale, which is type
    // too small to read. See MessageDocument for the decision.
    settings.useWideViewPort = false
    settings.loadWithOverviewMode = false

    // Both bars off, and the horizontal one is not cosmetic: the wrapper
    // element scrolls, not the page, so a page-level bar would advertise a
    // scroll that cannot happen.
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false

    // The body is laid out at its full height inside a list that scrolls, so
    // the WebView has nothing of its own to fling. Leaving this on lets a
    // rounding error in the measured height become a momentum scroll that eats
    // the gesture -- see ReaderWebView.
    overScrollMode = View.OVER_SCROLL_NEVER
}

/**
 * Which tapped links leave the app, and which are dropped on the floor.
 *
 * Pure, and separate from the client, because every clause here is a decision about untrusted
 * content and none of them can be checked by looking at a WebView.
 *
 * **An allow-list, not a block-list.** A message is arbitrary HTML from a stranger, and
 * `startActivity` on a scheme nobody considered is how a mail client becomes a way to poke every
 * other app on the phone. `intent:` is the sharp one — it encodes a component and extras, and
 * handing one to the system from message content lets the sender choose what to launch.
 * `javascript:` and `file:` are the other two worth naming. All three fail closed here by not being
 * on the list.
 *
 * **`http`, `https`, `mailto`, `tel`** are what a mail actually carries, and each is handed to the
 * system rather than resolved here: the user's chosen browser opens the web link, and `mailto:` is
 * a scheme this app now claims itself, so it comes back into the composer.
 *
 * **A gesture is required.** Without it a message could navigate on load — a `<meta refresh>`, a
 * redirect — and open a browser at a page nobody asked for, which for a mail client is a tracker
 * that also gets a foreground window. Only a real tap counts.
 *
 * **Main frame only.** A nested frame navigating is the frame's own business and is blocked
 * elsewhere anyway; it is not the user following a link.
 */
internal fun linkToOpen(url: Uri, hasGesture: Boolean, isForMainFrame: Boolean): Uri? {
    if (!hasGesture || !isForMainFrame) return null

    val scheme = url.scheme?.lowercase() ?: return null

    return url.takeIf { scheme in OPENABLE_SCHEMES }
}

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto", "tel")
