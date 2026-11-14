package com.noted.app.data.html

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class NoteMarkdownTextTest {

    private fun toHtml(markdown: String) = NoteMarkdown.toHtml(markdown, emptyList())

    private fun fromHtml(html: String) = NoteMarkdown.fromHtml(html, emptyList())

    /** El par exacto en los dos sentidos. */
    private fun assertPair(markdown: String, html: String) {
        assertEquals(html, toHtml(markdown))
        assertEquals(markdown, fromHtml(html))
    }

    /** Una forma no canónica: va al HTML de la canónica, y la canónica vuelve igual. */
    private fun assertNormalizes(markdown: String, canonical: String, html: String) {
        assertEquals(html, toHtml(markdown))
        assertEquals(canonical, fromHtml(html))
        assertEquals(html, toHtml(canonical))
    }

    @Test
    fun paragraphsAndBlankLines() {
        assertPair("hola\n\nchau", "<div>hola</div><div><br></div><div>chau</div>")
        assertPair("\nhola\n", "<div><br></div><div>hola</div><div><br></div>")
        // Un renglón con solo espacios se guarda tal cual (no se pierde nada).
        assertPair("a\n   \nb", "<div>a</div><div>   </div><div>b</div>")
        assertPair("  con sangría", "<div>  con sangría</div>")
    }

    @Test
    fun emptyMarkdownIsEmptyHtml() {
        assertPair("", "")
    }

    @Test
    fun headingsOneToSix() {
        assertPair(
            "# a\n## b\n### c\n#### d\n##### e\n###### f",
            "<h1>a</h1><h2>b</h2><h3>c</h3><h4>d</h4><h5>e</h5><h6>f</h6>",
        )
        // Siete numerales o sin espacio no son título (MarkdownVisualTransformation).
        assertPair("####### g\n#h\n  # i", "<div>####### g</div><div>#h</div><div>  # i</div>")
    }

    @Test
    fun bulletList() {
        assertPair("- a\n- b\n- c", "<ul><li>a</li><li>b</li><li>c</li></ul>")
    }

    @Test
    fun nestedBulletList() {
        assertPair("- a\n  - b", "<ul><li>a</li><ul><li>b</li></ul></ul>")
        assertPair(
            "- a\n  - b\n    - c\n- d",
            "<ul><li>a</li><ul><li>b</li><ul><li>c</li></ul></ul><li>d</li></ul>",
        )
    }

    @Test
    fun listLevelsJumpAndMix() {
        // Dos Tab seguidos: el ítem salta dos niveles; la lista del medio no tiene ítems.
        assertPair(
            "- a\n    - b\n  1. c\n  2. d\n- [ ] e",
            "<ul><li>a</li><ul><ul><li>b</li></ul></ul><ol><li>c</li><li>d</li></ol>" +
                "<li data-checked=\"false\">e</li></ul>",
        )
        // Un ítem con sangría sin ítem arriba.
        assertPair("  - a\n- b", "<ul><ul><li>a</li></ul><li>b</li></ul>")
        // Viñetas y casilleros seguidos van en la misma <ul>; una numerada abre otra lista.
        assertPair(
            "- a\n- [x] b\n1. c\n- d",
            "<ul><li>a</li><li data-checked=\"true\">b</li></ul><ol><li>c</li></ol><ul><li>d</li></ul>",
        )
    }

    @Test
    fun orderedListWithStart() {
        assertPair("3. a\n4. b", "<ol start=\"3\"><li>a</li><li>b</li></ol>")
        assertPair("1. a\n2. b", "<ol><li>a</li><li>b</li></ol>")
        // La numeración sigue después de una sublista, como la dibuja hoy el editor.
        assertPair(
            "1. a\n  1. x\n  2. y\n2. b",
            "<ol><li>a</li><ol><li>x</li><li>y</li></ol><li>b</li></ol>",
        )
        // Un renglón vacío corta la lista: la segunda empieza en su propio número.
        assertPair(
            "1. a\n\n2. b",
            "<ol><li>a</li></ol><div><br></div><ol start=\"2\"><li>b</li></ol>",
        )
    }

    @Test
    fun checklist() {
        assertPair(
            "- [ ] a\n- [x] b",
            "<ul><li data-checked=\"false\">a</li><li data-checked=\"true\">b</li></ul>",
        )
    }

    @Test
    fun quoteLines() {
        assertPair("> a\n> b", "<blockquote>a<br>b</blockquote>")
        assertPair(
            "> a\n\n> b",
            "<blockquote>a</blockquote><div><br></div><blockquote>b</blockquote>",
        )
    }

    @Test
    fun emptyBlocksCarryBr() {
        assertPair("# ", "<h1><br></h1>")
        assertPair("- ", "<ul><li><br></li></ul>")
        assertPair("- [ ] ", "<ul><li data-checked=\"false\"><br></li></ul>")
        assertPair("1. ", "<ol><li><br></li></ol>")
        assertPair("> ", "<blockquote><br></blockquote>")
        // Último renglón de la cita vacío: un <br> más, como en el navegador.
        assertPair("> a\n> ", "<blockquote>a<br><br></blockquote>")
        assertPair("> \n> a", "<blockquote><br>a</blockquote>")
    }

    @Test
    fun codeFenceKeepsTextAndLanguage() {
        assertPair(
            "```kotlin\nval x = 1\n\n  if (a < b) **no** {}\n```",
            "<pre spellcheck=\"false\" data-language=\"kotlin\">val x = 1\n\n  if (a &lt; b) **no** {}</pre>",
        )
        assertPair("```\nsin lenguaje\n```", "<pre spellcheck=\"false\">sin lenguaje</pre>")
        assertPair("```\n```", "<pre spellcheck=\"false\"></pre>")
        assertPair("```\nx\n\n```", "<pre spellcheck=\"false\">x\n\n</pre>")
    }

    @Test
    fun codeBlockStartingWithBlankLineSurvives() {
        // El parser de HTML se come un salto pegado a <pre>: el primer salto va como <br>.
        val markdown = "```\n\nx\n```"
        val html = "<pre spellcheck=\"false\"><br>x</pre>"
        assertPair(markdown, html)
        assertEquals(html, toHtml(fromHtml(toHtml(markdown))))
        assertPair("```\n\n```", "<pre spellcheck=\"false\"><br></pre>")
        assertPair("```\n\n\n\nx\n```", "<pre spellcheck=\"false\"><br>\n\nx</pre>")
    }

    @Test
    fun unclosedCodeFenceStaysText() {
        // Sin cierre, el editor muestra el resto como código; se guarda como texto tal cual.
        assertPair(
            "antes\n```kotlin\n# no es título\n* tampoco lista",
            "<div>antes</div><div>```kotlin</div><div># no es título</div><div>* tampoco lista</div>",
        )
    }

    @Test
    fun inexactFencesKeepWholeLines() {
        // MVT acepta como cierre cualquier "```algo" y dibuja los cerrojos enteros: si no son
        // exactos, el bloque va tal cual y se sigue leyendo después del cierre.
        assertPair(
            "```js\na\n```py\nb\n```",
            "<div>```js</div><div>a</div><div>```py</div><div>b</div><div>```</div>",
        )
        assertPair(
            "  ```kotlin\nx\n```\n# t",
            "<div>  ```kotlin</div><div>x</div><div>```</div><h1>t</h1>",
        )
        assertPair(
            "```\n**x**\n``` \n```c\ny\n```",
            "<div>```</div><div>**x**</div><div>``` </div><pre spellcheck=\"false\" data-language=\"c\">y</pre>",
        )
    }

    @Test
    fun divider() {
        assertPair("---", "<hr>")
        assertNormalizes("***", "---", "<hr>")
        assertNormalizes("___", "---", "<hr>")
        assertNormalizes("  -----  ", "---", "<hr>")
        // "****" y "******" no son separador: son negrita vacía (MarkdownVisualTransformation).
        assertPair("****", "<div><b></b></div>")
    }

    @Test
    fun inlineFormats() {
        assertPair(
            "***a*** **b** *c* ~~d~~ `e`",
            "<div><b><i>a</i></b> <b>b</b> <i>c</i> <strike>d</strike> <code spellcheck=\"false\">e</code></div>",
        )
        assertPair("*a **b** c*", "<div><i>a <b>b</b> c</i></div>")
        assertPair("**a ~~b~~**", "<div><b>a <strike>b</strike></b></div>")
        // Una marca pegada a un espacio o entre números no abre formato.
        assertPair("2*3*4 y * no", "<div>2*3*4 y * no</div>")
    }

    @Test
    fun inlineLink() {
        assertPair(
            "ver [sitio](https://es.wikipedia.org/wiki/Mate_(bebida)) ya",
            "<div>ver <a href=\"https://es.wikipedia.org/wiki/Mate_(bebida)\">sitio</a> ya</div>",
        )
        assertPair(
            "[a & b](https://x.com/?q=1&r=\"2\")",
            "<div><a href=\"https://x.com/?q=1&amp;r=&quot;2&quot;\">a &amp; b</a></div>",
        )
    }

    @Test
    fun literalHtmlCharsSurvive() {
        assertPair("a < b && \"c\"", "<div>a &lt; b &amp;&amp; \"c\"</div>")
        assertPair(
            "<b>x</b> &amp; 'y' > z",
            "<div>&lt;b&gt;x&lt;/b&gt; &amp;amp; 'y' &gt; z</div>",
        )
    }

    @Test
    fun escapedLinesSurvive() {
        // La "\" solo es escape en un renglón que parece token (adjunto, nota, tarjeta, desplegable).
        assertPair("\\- no es lista", "<div>\\- no es lista</div>")
        assertPair(
            "\\[x](noted://note/1)",
            "<div><a href=\"noted://note/1\">x</a></div>",
        )
        assertPair("\\<details>", "<div>&lt;details&gt;</div>")
        assertPair("  \\[f](noted://att/2)", "<div>  <a href=\"noted://att/2\">f</a></div>")
        // Con dos "\" se saca una y la otra queda a la vista.
        assertPair(
            "\\\\[x](noted://note/1)",
            "<div>\\<a href=\"noted://note/1\">x</a></div>",
        )
        // Dentro de un bloque de código también.
        assertPair(
            "```\n\\<summary>t</summary>\n```",
            "<pre spellcheck=\"false\">&lt;summary&gt;t&lt;/summary&gt;</pre>",
        )
    }

    @Test
    fun unclosedMarksStayLiteral() {
        assertPair("`x", "<div>`x</div>")
        assertPair("[a](b", "<div>[a](b</div>")
        assertPair("*hola", "<div>*hola</div>")
        assertPair("~~hola", "<div>~~hola</div>")
        // Sin romper el resto del renglón.
        assertPair("`x y **b**", "<div>`x y <b>b</b></div>")
        assertPair("[a](b y *c*", "<div>[a](b y <i>c</i></div>")
        // "**" sin cierre no es negrita: MarkdownVisualTransformation lo dibuja como la
        // cursiva vacía que deja el botón Cursiva sin selección, y esconde los dos "*".
        assertPair("**hola", "<div><i></i>hola</div>")
    }

    private val colors = listOf("red", "orange", "yellow", "green", "blue", "pink", "purple", "gray")

    @Test
    fun underlineRoundTrips() {
        assertPair("a <u>b</u> c", "<div>a <u>b</u> c</div>")
        assertPair("<u>**b** y *c*</u>", "<div><u><b>b</b> y <i>c</i></u></div>")
        assertPair("# <u>t</u>", "<h1><u>t</u></h1>")
        assertPair("- <u>a</u>", "<ul><li><u>a</u></li></ul>")
        // Sin cierre no es subrayado: queda como texto, con "\" delante para que vuelva igual.
        assertPair("\\<u>a", "<div>&lt;u&gt;a</div>")
    }

    @Test
    fun subSupRoundTrip() {
        assertPair("H<sub>2</sub>O y x<sup>2</sup>", "<div>H<sub>2</sub>O y x<sup>2</sup></div>")
        assertPair("<sup>**a**</sup>", "<div><sup><b>a</b></sup></div>")
    }

    @Test
    fun textColorRoundTrips() {
        for (color in colors) {
            assertPair(
                "a <span class=\"noted-text-$color\">b</span> c",
                "<div>a <span class=\"noted-text-$color\">b</span> c</div>",
            )
        }
        assertPair(
            "<span class=\"noted-text-red\">**b**</span>",
            "<div><span class=\"noted-text-red\"><b>b</b></span></div>",
        )
    }

    @Test
    fun greenHighlightExportsAsEquals() {
        assertPair("a ==b== c", "<div>a <span class=\"noted-highlight\">b</span> c</div>")
        assertPair("==**b**==", "<div><span class=\"noted-highlight\"><b>b</b></span></div>")
        // Los espacios de los bordes salen de la marca, que si no, no vuelve a leerse.
        assertEquals("a ==b== c", fromHtml("<div>a<span class=\"noted-highlight\"> b </span>c</div>"))
    }

    @Test
    fun sTagExportsAsTildes() {
        assertEquals("a ~~b~~ c", fromHtml("<div>a <s>b</s> c</div>"))
        assertEquals("a ~~b~~ c", fromHtml("<div>a <strike>b</strike> c</div>"))
        assertEquals("<div>a <strike>b</strike> c</div>", toHtml("a ~~b~~ c"))
    }

    @Test
    fun greenHighlightOverLinkWithEqualsRoundTrips() {
        assertPair(
            "==[l](https://x/?t=YQ==)==",
            "<div><span class=\"noted-highlight\"><a href=\"https://x/?t=YQ==\">l</a></span></div>",
        )
    }

    @Test
    fun greenHighlightOverCodeWithEqualsRoundTrips() {
        assertPair(
            "==`a==b`==",
            "<div><span class=\"noted-highlight\"><code spellcheck=\"false\">a==b</code></span></div>",
        )
    }

    @Test
    fun underlineOverCodeWithClosingTagRoundTrips() {
        assertPair(
            "<u>`</u>` y</u>",
            "<div><u><code spellcheck=\"false\">&lt;/u&gt;</code> y</u></div>",
        )
        assertPair(
            "<span class=\"noted-text-red\">`</span>`</span>",
            "<div><span class=\"noted-text-red\"><code spellcheck=\"false\">&lt;/span&gt;</code></span></div>",
        )
    }

    @Test
    fun equalsBeforeGreenHighlightRoundTrips() {
        // Con un "=" pegado, el verde sale como los otros colores, no como "==".
        assertPair(
            "a=<span class=\"noted-highlight\">x</span>",
            "<div>a=<span class=\"noted-highlight\">x</span></div>",
        )
        assertPair("a=b ==x==", "<div>a=b <span class=\"noted-highlight\">x</span></div>")
        assertPair("a= ==x==", "<div>a= <span class=\"noted-highlight\">x</span></div>")
    }

    @Test
    fun equalsAfterGreenHighlightRoundTrips() {
        assertPair(
            "<span class=\"noted-highlight\">x</span>=y",
            "<div><span class=\"noted-highlight\">x</span>=y</div>",
        )
        assertPair(
            "<span class=\"noted-highlight\">x</span>\\==",
            "<div><span class=\"noted-highlight\">x</span>==</div>",
        )
    }

    @Test
    fun greenHighlightStartingWithEqualsRoundTrips() {
        for (inner in listOf("=x", "x=", "=x=", "==")) {
            val html = "<div>a <span class=\"noted-highlight\">$inner</span> b</div>"
            assertEquals(html, toHtml(fromHtml(html)))
        }
        assertPair(
            "<span class=\"noted-highlight\">=x</span>",
            "<div><span class=\"noted-highlight\">=x</span></div>",
        )
    }

    @Test
    fun emptyGreenHighlightRoundTrips() {
        assertPair(
            "<span class=\"noted-highlight\"></span>",
            "<div><span class=\"noted-highlight\"></span></div>",
        )
    }

    @Test
    fun otherHighlightRoundTrips() {
        for (color in colors.filter { it != "green" }) {
            assertPair(
                "a <span class=\"noted-highlight-$color\">b</span> c",
                "<div>a <span class=\"noted-highlight-$color\">b</span> c</div>",
            )
        }
        // Color y resaltado en un mismo span salen anidados.
        assertEquals(
            "<span class=\"noted-text-red\">==x==</span>",
            fromHtml("<div><span class=\"noted-text-red noted-highlight\">x</span></div>"),
        )
    }

    @Test
    fun literalAngleAndEqualsRoundTrip() {
        val texts = listOf(
            "<u>x</u>", "</span>", "<sub>a</sub>", "a == b", "===", "====", "a==b==c",
            "\\<u>x</u>", "\\\\==x==", "<span class=\"noted-text-red\">x</span>", "a < b", "x\\",
        )
        for (text in texts) {
            val html = "<div>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</div>"
            val markdown = fromHtml(html)
            assertEquals("no vuelve igual: $markdown", html, toHtml(markdown))
        }
        assertEquals("\\<u>x\\</u>", fromHtml("<div>&lt;u&gt;x&lt;/u&gt;</div>"))
        assertEquals("a \\==b\\==", fromHtml("<div>a ==b==</div>"))
        // Una "\" suelta o delante de otra cosa no se toca.
        assertEquals("a\\b \\*", fromHtml("<div>a\\b \\*</div>"))
        // La marca de verdad después de una "\" del texto: se duplica.
        assertEquals("\\\\<u>x</u>", fromHtml("<div>\\<u>x</u></div>"))
    }

    @Test
    fun migrationKeepsTagsAndEqualsAsText() {
        // En una nota vieja en Markdown, "<u>" y "==" eran texto: la migración no los lee como marcas.
        assertEquals(
            "<div>&lt;u&gt;a&lt;/u&gt; ==b== \\&lt;u&gt;</div>",
            NoteMarkdown.toHtml("<u>a</u> ==b== \\<u>",emptyList(), null, inlineMarks = false),
        )
    }

    @Test
    fun canonicalRoundTrip() {
        val body = TestBodies.canonicalMarkdown
        val html = toHtml(body)
        assertEquals(body, fromHtml(html))
        assertEquals(html, toHtml(fromHtml(html)))
    }

    @Test
    fun nonCanonicalIsStable() {
        assertNormalizes("* a", "- a", "<ul><li>a</li></ul>")
        assertNormalizes("+ a", "- a", "<ul><li>a</li></ul>")
        assertNormalizes("1) a", "1. a", "<ol><li>a</li></ol>")
        assertNormalizes("1. a\n1. b\n1. c", "1. a\n2. b\n3. c", "<ol><li>a</li><li>b</li><li>c</li></ol>")
        assertNormalizes("* [X] a", "- [x] a", "<ul><li data-checked=\"true\">a</li></ul>")
        assertNormalizes("#   a", "# a", "<h1>a</h1>")
        assertNormalizes(">a", "> a", "<blockquote>a</blockquote>")
        // Un Tab son cuatro espacios (dos niveles), como lo dibuja el editor.
        assertNormalizes("- a\n\t- b", "- a\n    - b", "<ul><li>a</li><ul><ul><li>b</li></ul></ul></ul>")
    }

    /**
     * El contrato para cualquier texto: toHtml(fromHtml(toHtml(md))) == toHtml(md), y lo que
     * devuelve fromHtml (canónico) vuelve idéntico, con la misma cantidad de renglones.
     */
    @Test
    fun randomBodiesKeepContract() {
        val prefixes = listOf(
            "", "", "", "# ", "###### ", "####### ", "#", "- ", "* ", "+ ", "  - ", "    - ", " - ",
            "\t- ", "- [ ] ", "- [x] ", "* [X] ", "- [ ]", "1. ", "3) ", "  2. ", "0. ", "1234. ",
            "> ", ">", "  > ", "\\", "\\\\", "  ",
        )
        val wholeLines = listOf(
            "", "", "   ", "```", "```kotlin", "```py", " ``` x", "``` ", "---", "***", "****", "******", "___", "- - -",
            "[a](noted://note/1)", "\\[a](noted://note/1)", "[f](noted://att/2)", "<details>",
            "<details open>", "<summary>t</summary>", "</details>", "<!-- noted:bookmark b1 -->",
        )
        val pieces = listOf(
            "a", "b", "hola", " ", " ", "*", "**", "***", "~~", "~", "`", "[", "]", "(", ")", "](",
            "[x](u)", "[x](a(b)c)", "2", "<", ">", "&", "\"", "'", "\\", "#", "\t", "&amp;",
            "<u>", "</u>", "<sub>", "</sub>", "<sup>", "</sup>", "<span class=\"noted-text-red\">",
            "<span class=\"noted-highlight-blue\">", "</span>", "==", "=", "\\<", "\\==",
        )
        val random = Random(20261101L)
        repeat(3000) {
            val lines = List(1 + random.nextInt(12)) {
                if (random.nextInt(4) == 0) {
                    wholeLines[random.nextInt(wholeLines.size)]
                } else {
                    prefixes[random.nextInt(prefixes.size)] +
                        List(random.nextInt(7)) { pieces[random.nextInt(pieces.size)] }.joinToString("")
                }
            }
            val markdown = lines.joinToString("\n")
            val html = toHtml(markdown)
            val canonical = fromHtml(html)
            // Ninguna normalización de texto agrega ni saca renglones. Un desplegable sí: sin
            // </details> lo gana, y con un solo renglón vacío adentro lo pierde, como en el
            // editor (NoteMarkdownBlocksTest).
            val hasToggle = lines.indices.any { k ->
                NoteMarkdown.DETAILS_OPEN.matches(lines[k].trim()) &&
                    lines.getOrNull(k + 1)?.trim()?.let { NoteMarkdown.SUMMARY.matches(it) } == true
            }
            if (!hasToggle) {
                assertEquals("cambió la cantidad de renglones: $markdown", lines.size, canonical.split('\n').size)
            }
            assertEquals("toHtml no es estable para: $markdown", html, toHtml(canonical))
            assertEquals("la forma canónica no vuelve igual: $canonical", canonical, fromHtml(toHtml(canonical)))
        }
    }
}
