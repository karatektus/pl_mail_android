package de.plmail.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The markup a decrypted message keeps, and what it loses.
 *
 * The forbidden list is the web app's DOMPurify configuration; each case below is something a
 * hostile sender could put inside an encrypted message precisely because no server sanitises it.
 */
class DecryptedHtmlTest {

    @Test
    fun `nothing that runs survives`() {
        val clean =
            DecryptedHtml.sanitize(
                """<p onclick="steal()" onmouseover="x()">Hi</p><script>alert(1)</script>""" +
                    """<a href="javascript:alert(2)">link</a><img src="x" onerror="boom()">"""
            )

        assertFalse("script" in clean, clean)
        assertFalse("onclick" in clean, clean)
        assertFalse("onmouseover" in clean, clean)
        assertFalse("onerror" in clean, clean)
        assertFalse("javascript:" in clean, clean)
        assertTrue("<p>Hi</p>" in clean, clean)
    }

    @Test
    fun `nothing that embeds, submits or redirects survives`() {
        val clean =
            DecryptedHtml.sanitize(
                """<iframe src="https://evil.example"></iframe><object data="x"></object>""" +
                    """<embed src="x"><form action="https://evil.example"><input name="pw"><button>Go</button></form>""" +
                    """<base href="https://evil.example/"><meta http-equiv="refresh" content="0;url=x">""" +
                    """<link rel="stylesheet" href="https://evil.example/s.css">"""
            )

        listOf("iframe", "object", "embed", "form", "input", "button", "<base", "<meta", "<link")
            .forEach {
                assertFalse(it in clean, "$it in $clean")
            }
    }

    @Test
    fun `the markup mail is made of stays`() {
        val html =
            """<table width="600" cellpadding="0" style="border:0"><tr><td bgcolor="#fff" align="center">""" +
                """<font face="Arial" color="#333"><b>Bold</b> <a href="https://example.org">site</a></font>""" +
                """</td></tr></table>"""

        val clean = DecryptedHtml.sanitize(html)

        assertTrue("""width="600"""" in clean, clean)
        assertTrue("""style="border:0"""" in clean, clean)
        assertTrue("""bgcolor="#fff"""" in clean, clean)
        assertTrue("""<font face="Arial" color="#333">""" in clean, clean)
        assertTrue("""href="https://example.org"""" in clean, clean)
    }

    @Test
    fun `cid and data images keep their source, srcset goes`() {
        val clean =
            DecryptedHtml.sanitize(
                """<img src="cid:logo@example.net"><img src="data:image/png;base64,AAAA">""" +
                    """<img src="https://tracker.example/p.gif" srcset="https://a 2x">"""
            )

        assertTrue("""src="cid:logo@example.net"""" in clean, clean)
        assertTrue("""src="data:image/png;base64,AAAA"""" in clean, clean)
        // Kept as a remote image, which the reader blocks until pictures are allowed.
        assertTrue("""src="https://tracker.example/p.gif"""" in clean, clean)
        assertFalse("srcset" in clean, clean)
    }

    @Test
    fun `plain text is escaped, never interpreted`() {
        assertEquals(
            "<pre>1 &lt; 2 &amp;&amp; &lt;b&gt;not bold&lt;/b&gt;</pre>",
            DecryptedHtml.fromText("1 < 2 && <b>not bold</b>"),
        )
    }
}
