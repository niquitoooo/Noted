package com.noted.app.data.html

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlSanitizerTest {

    @Test
    fun keepsAllowedTags() {
        val html = "<div><b>a</b><i>b</i><u>c</u><strike>d</strike></div>"
        assertEquals(html, HtmlSanitizer.clean(html))
    }

    @Test
    fun convertsSynonyms() {
        assertEquals("<div>x</div>", HtmlSanitizer.clean("<p>x</p>"))
        assertEquals("<b>a</b>", HtmlSanitizer.clean("<strong>a</strong>"))
        assertEquals("<i>a</i>", HtmlSanitizer.clean("<em>a</em>"))
        assertEquals("<s>a</s>", HtmlSanitizer.clean("<del>a</del>"))
    }

    @Test
    fun convertsBlockSynonymsToDiv() {
        assertEquals("<div>x</div>", HtmlSanitizer.clean("<dt>x</dt>"))
        assertEquals("<div>x</div>", HtmlSanitizer.clean("<article>x</article>"))
        assertEquals("<div>x</div>", HtmlSanitizer.clean("<section>x</section>"))
    }

    @Test
    fun dropsScriptsAndHandlers() {
        assertEquals(
            "<div>a</div>",
            HtmlSanitizer.clean("""<div onclick="x">a<script>bad()</script></div>"""),
        )
    }

    @Test
    fun keepsDataAttributes() {
        val html = listOf(
            """<div data-checked="true">a</div>""",
            """<a data-note-id="n1" data-non-editable="true" href="x">t</a>""",
            """<a data-file-id="f1" data-non-editable="true" href="x">t</a>""",
            """<div data-noted-bookmark="b1"><a href="x">t</a></div>""",
        ).joinToString("")
        assertEquals(html, HtmlSanitizer.clean(html))
    }

    @Test
    fun keepsCustomSchemes() {
        val html = """<div><a href="noted://att/1">f</a></div>""" +
            """<div><a href="noted://open-note?id=2">n</a></div>""" +
            """<div><img src="noted://att/3"></div>"""
        assertEquals(html, HtmlSanitizer.clean(html))
    }

    @Test
    fun blocksScriptUrls() {
        for (url in listOf("javascript:alert(1)", " JavaScript:alert(1)", "\tjavascript:alert(1)", "java\tscript:alert(1)", "vbscript:x")) {
            assertEquals(
                url,
                "<div>x</div>",
                HtmlSanitizer.clean("<div><a href=\"$url\">x</a></div>"),
            )
        }
        assertEquals(
            "<div><object></object></div>",
            HtmlSanitizer.clean("""<div><object data="javascript:alert(1)"></object></div>"""),
        )
    }

    @Test
    fun blocksDataUrls() {
        assertEquals(
            "<div>y</div>",
            HtmlSanitizer.clean("""<div><a href="data:text/html,x">y</a></div>"""),
        )
        assertEquals(
            "<div></div>",
            HtmlSanitizer.clean("""<div><img src="data:image/png;base64,AA"></div>"""),
        )
    }

    @Test
    fun keepsNoteLinkWithoutHref() {
        val html = """<a data-note-id="n1" data-non-editable="true">T</a>"""
        assertEquals(html, HtmlSanitizer.clean(html))
    }

    @Test
    fun keepsSafeUrls() {
        val html = """<div><a href="https://example.com/a?b=1">a</a></div>""" +
            """<div><a href="mailto:a@b.com">m</a></div>""" +
            """<div><a href="noted://att/1">f</a></div>""" +
            """<div><a href="noted://open-note?id=2">n</a></div>"""
        assertEquals(html, HtmlSanitizer.clean(html))
    }

    @Test
    fun unwrapsFont() {
        assertEquals("a", HtmlSanitizer.clean("""<font color="red">a</font>"""))
    }

    @Test
    fun keepsCollapsibleSectionUntouched() {
        val html = """<div class="noted-collapsible-section noted-section-collapsed">""" +
            """<div class="noted-section-title-wrapper">""" +
            """<div class="noted-section-title noted-placeholder" data-noted-placeholder-key="title" data-noted-placeholder="Title">""" +
            """<div class="noted-section-title-inner"><h3>T</h3></div></div></div>""" +
            """<div class="noted-section-content noted-placeholder" data-noted-placeholder-key="content">""" +
            """<div class="noted-section-content-inner"><div>x</div></div></div></div>"""
        assertEquals(html, HtmlSanitizer.clean(html))
    }

    @Test
    fun keepsListAndCodeAttributes() {
        val html = """<ol start="3"><li>a</li></ol>""" +
            """<pre spellcheck="false" data-language="kotlin">val x = 1</pre>""" +
            """<div><code spellcheck="false">y</code></div>"""
        assertEquals(html, HtmlSanitizer.clean(html))
    }

    @Test
    fun keepsTextEscapesAndLineBreaks() {
        val html = "<div>1 &lt; 2 &amp;&amp; 3 &gt; 2</div><div><br></div><div>\"ok\"</div>"
        assertEquals(html, HtmlSanitizer.clean(html))
    }

    // ---- pegar ----

    @Test
    fun styledBoldSpanBecomesB() {
        assertEquals(
            "<div><b>a</b><b><i>b</i></b>c<u><s>d</s></u></div>",
            HtmlSanitizer.cleanPasted(
                """<div><span style="font-weight:700">a</span>""" +
                    """<span style="font-weight: bold; font-style: italic">b</span>""" +
                    """<span style="font-weight:400">c</span>""" +
                    """<span style="text-decoration: underline line-through">d</span></div>""",
            ),
        )
    }

    @Test
    fun styleAttributeIsRemoved() {
        assertEquals(
            """<div><b>a</b></div><pre spellcheck="false">c</pre><div><code spellcheck="false">d</code></div>""",
            HtmlSanitizer.cleanPasted(
                """<div style="color:red"><b style="font-size:20px">a</b></div>""" +
                    """<pre style="background:#eee">c</pre><div><code style="color:blue">d</code></div>""",
            ),
        )
        // Lo mismo para guardar: clean no cambia.
        assertEquals("""<div style="color:red">a</div>""", HtmlSanitizer.clean("""<div style="color:red">a</div>"""))
    }

    @Test
    fun linksWithoutHrefAreUnwrapped() {
        assertEquals(
            """<div>ab<a href="https://x.com">c</a></div>""",
            HtmlSanitizer.cleanPasted("""<div><a name="x">a</a><a href="data:text/html,x">b</a><a href="https://x.com">c</a></div>"""),
        )
        // Un enlace a nota sin href se rearma.
        assertEquals(
            """<div><a data-note-id="n1" href="noted://open-note?id=n1">t</a></div>""",
            HtmlSanitizer.cleanPasted("""<div><a data-note-id="n1">t</a></div>"""),
        )
    }

    @Test
    fun divsInsideHeadingsAndItemsAreUnwrapped() {
        assertEquals(
            "<h2>t</h2><ul><li>a</li><li>b</li></ul>",
            HtmlSanitizer.cleanPasted("<h2><div>t</div></h2><ul><li><div>a</div></li><li></li><li>b</li></ul>"),
        )
    }

    @Test
    fun evernoteTodoBecomesChecklist() {
        assertEquals(
            """<ul><li data-checked="true">a</li><li data-checked="false">b</li></ul>""" +
                """<ul data-todo="true"><li data-checked="false">c</li></ul>""",
            HtmlSanitizer.cleanPasted(
                """<ul style="--en-todo:true;"><li style="--en-checked:true;"><div>a</div></li>""" +
                    """<li style="--en-checked:false;"><div>b</div></li></ul>""" +
                    """<ul data-todo="true"><li>c</li></ul>""",
            ),
        )
    }

    @Test
    fun imagesAreDropped() {
        assertEquals(
            "<div>ab</div>",
            HtmlSanitizer.cleanPasted("""<div>a<img src="https://x.com/y.png" height="10">b</div><img src="noted://att/1">"""),
        )
    }

    @Test
    fun paletteColorSpanBecomesClass() {
        assertEquals(
            """<div><span class="noted-text-red">a</span><span class="noted-highlight">b</span>""" +
                """<span class="noted-highlight-blue">c</span><span class="noted-text-blue"><b>d</b></span>ef</div>""",
            HtmlSanitizer.cleanPasted(
                """<div><span style="color: #F9241C">a</span>""" +
                    """<span style="background-color: rgb(231, 255, 205)">b</span>""" +
                    """<span style="background-color:#22547d">c</span>""" +
                    """<span style="font-weight:bold;color:#2070f7">d</span>""" +
                    """<span style="color:#123456">e</span><font color="red">f</font></div>""",
            ),
        )
    }

    @Test
    fun googleDocsNormalWeightWrapperIsNotBold() {
        // Google Docs envuelve todo en un <b> de peso normal.
        assertEquals(
            "<div>a<b>b</b></div>",
            HtmlSanitizer.cleanPasted(
                """<b style="font-weight:normal;" id="docs-internal-guid-1"><div>a""" +
                    """<span style="font-weight:700">b</span></div></b>""",
            ),
        )
        assertEquals("c", HtmlSanitizer.cleanPasted("""<strong style="font-weight: 400">c</strong>"""))
        // Sin peso en el style, o con peso de negrita, sigue en negrita.
        assertEquals("<b>d</b><b>e</b>", HtmlSanitizer.cleanPasted("""<b style="color:red">d</b><b style="font-weight:700">e</b>"""))
    }

    @Test
    fun importantWeightIsBold() {
        assertEquals(
            "<b>a</b><b>b</b>",
            HtmlSanitizer.cleanPasted(
                """<span style="font-weight: 700 !important">a</span><span style="font-weight:bold!important">b</span>""",
            ),
        )
    }

    @Test
    fun backgroundShorthandColor() {
        assertEquals(
            """<span class="noted-highlight-red">a</span><span class="noted-highlight-blue">b</span>""",
            HtmlSanitizer.cleanPasted(
                """<span style="background: rgb(254, 222, 222) none repeat scroll 0% 0%">a</span>""" +
                    """<span style="background: none #D8ECFD">b</span>""",
            ),
        )
    }
}
