package com.jot.app.data.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Attribute
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Saneado del HTML del cuerpo de una nota, con una lista de
 * etiquetas y atributos permitidos y algunas conversiones.
 */
object HtmlSanitizer {

    private const val DATA_PREFIX = "data-"

    private val tags = arrayOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "div", "span", "br", "hr",
        "b", "strong", "i", "em", "u", "strike", "s", "del",
        "ul", "ol", "li", "a", "img", "blockquote", "big", "small", "sup", "sub", "center",
        "pre", "code", "table", "tr", "th", "td", "thead", "colgroup", "col",
        "audio", "source", "video", "object", "iframe",
        "p", "dt", "article", "section",
    )

    // La lista de atributos vale para todas las etiquetas permitidas.
    private val attributes = arrayOf(
        "style", "title", "width", "height", "src", "alt", "href", "class", "controls", "type",
        "preload", "rowspan", "colspan", "spellcheck", "allow", "allowfullscreen", "frameborder",
        "loading", "name", "referrerpolicy", "data", "start",
    )

    // Después de limpiar.
    private val synonyms = mapOf(
        "p" to "div", "dt" to "div", "article" to "div", "section" to "div",
        "strong" to "b", "em" to "i", "del" to "s",
    )

    // Sin protocolos declarados, jsoup deja pasar cualquier esquema (jot://).
    // ALLOW_DATA_ATTR: jsoup no admite comodines, así que los data-* se aceptan a mano.
    private val safelist: Safelist = object : Safelist() {
        override fun isSafeAttribute(tagName: String, el: Element, attr: Attribute): Boolean =
            (attr.key.length > DATA_PREFIX.length && attr.key.startsWith(DATA_PREFIX)) ||
                super.isSafeAttribute(tagName, el, attr)
    }.addTags(*tags).addAttributes(":all", *attributes)

    /** Devuelve el HTML del cuerpo, sin `<html>` ni `<body>`. */
    fun clean(html: String): String {
        val clean = Cleaner(safelist).clean(Jsoup.parseBodyFragment(html))
        clean.outputSettings().prettyPrint(false)
        val body = clean.body()
        removeDangerousUrls(body)
        body.select(synonyms.keys.joinToString(",")).forEach { it.tagName(synonyms.getValue(it.normalName())) }
        return body.html()
    }

    /**
     * El HTML que se pega: [clean] y además los ganchos al pegar y lo que
     * hace al escribir con los colores. Las imágenes se descartan. [clean] no
     * cambia: el guardado lo sigue usando igual.
     */
    fun cleanPasted(html: String): String {
        val body = Jsoup.parseBodyFragment(clean(html)).body()
        body.ownerDocument()?.outputSettings()?.prettyPrint(false)
        // Las imágenes pegadas se descartan.
        body.select("img").remove()
        evernoteChecklists(body)
        // Un `<b>` de peso normal no es negrita: es el envoltorio de Google Docs.
        body.select("b[style]").filter { isNormalWeight(declarations(it)["font-weight"]) }.forEach { it.unwrap() }
        body.select("span[style]").forEach { styledSpan(it) }
        body.select("a").forEach { link ->
            when {
                link.hasAttr("href") -> Unit
                link.hasAttr("data-note-id") -> link.attr("href", NOTE_URL + link.attr("data-note-id"))
                else -> link.unwrap()
            }
        }
        body.select("code, pre").forEach { it.attr("spellcheck", "false") }
        body.select("[style]").forEach { it.removeAttr("style") }
        // Los spans sin clase no dan formato.
        body.select("span").filter { it.classNames().isEmpty() }.forEach { it.unwrap() }
        body.select("h1 div, h2 div, h3 div, h4 div, h5 div, h6 div, li div").reversed().forEach { it.unwrap() }
        body.select("li").filter { it.childNodeSize() == 0 || (it.children().isEmpty() && it.text().isBlank()) }
            .forEach { it.remove() }
        return body.html()
    }

    /** Las declaraciones de un `style`, con el nombre y el valor en minúsculas y sin `!important`. */
    private fun declarations(element: Element): Map<String, String> =
        element.attr("style").split(';').mapNotNull { part ->
            val colon = part.indexOf(':')
            if (colon <= 0) null
            else part.substring(0, colon).trim().lowercase() to
                part.substring(colon + 1).trim().lowercase().removeSuffix("!important").trim()
        }.toMap()

    /** `font-weight` de negrita: `bold`, `bolder` o más de 400. */
    private fun isBoldWeight(weight: String?): Boolean =
        weight == "bold" || weight == "bolder" || (weight?.toIntOrNull() ?: 0) > 400

    /** `font-weight` que no es negrita: `normal`, `lighter` o hasta 400. */
    private fun isNormalWeight(weight: String?): Boolean =
        weight == "normal" || weight == "lighter" || weight?.toIntOrNull()?.let { it <= 400 } == true

    /** Evernote: `ul` con `--en-todo:true` o `data-todo` es una checklist; `--en-checked` la tilda. */
    private fun evernoteChecklists(body: Element) {
        for (list in body.select("ul")) {
            val todo = list.hasAttr("data-todo") || declarations(list)["--en-todo"]?.lowercase() == "true"
            if (!todo) continue
            for (item in list.children()) {
                if (item.normalName() != "li" || item.hasAttr("data-checked")) continue
                val checked = declarations(item)["--en-checked"]?.lowercase() == "true"
                item.attr("data-checked", checked.toString())
            }
        }
    }

    /**
     * Un `span` con estilo: el peso, la cursiva, el subrayado y el tachado pasan a etiquetas
     * y el color de la paleta a su clase. El `style` se quita después.
     */
    private fun styledSpan(span: Element) {
        val style = declarations(span)
        val decoration = listOfNotNull(style["text-decoration"], style["text-decoration-line"]).joinToString(" ")
        val tags = listOfNotNull(
            "b".takeIf { isBoldWeight(style["font-weight"]) },
            "i".takeIf { style["font-style"] == "italic" },
            "u".takeIf { "underline" in decoration },
            "s".takeIf { "line-through" in decoration },
        )
        if (tags.isNotEmpty()) {
            val children = span.childNodes().toList()
            var inner = span
            for (tag in tags) inner = inner.appendElement(tag)
            children.forEach { inner.appendChild(it) }
        }
        style["color"]?.let(::parseColor)?.let { color ->
            TEXT_COLORS.entries.firstOrNull { color in it.value }?.let { span.addClass("jot-text-${it.key}") }
        }
        val background = style["background-color"]?.let(::parseColor) ?: style["background"]?.let(::shorthandColor)
        background?.let { color ->
            HIGHLIGHT_COLORS.entries.firstOrNull { color in it.value }?.let {
                span.addClass(if (it.key == "green") "jot-highlight" else "jot-highlight-${it.key}")
            }
        }
    }

    private val hexColor = Regex("^#([0-9a-f]{3}|[0-9a-f]{6})$")
    private val rgbColor = Regex("""^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(,\s*[0-9.]+\s*)?\)$""")

    private val colorInShorthand = Regex("""#[0-9a-f]{3,6}\b|rgba?\([^)]*\)""")

    /** El color de un atajo `background` con más valores (`rgb(…) none repeat`), o `null`. */
    private fun shorthandColor(value: String): String? =
        colorInShorthand.findAll(value).firstNotNullOfOrNull { parseColor(it.value) }

    /** Un color de CSS como `#rrggbb` en minúsculas, o `null` si no es hex ni `rgb()`. */
    private fun parseColor(value: String): String? {
        val color = value.trim().lowercase().removeSuffix("!important").trim()
        hexColor.matchEntire(color)?.let { match ->
            val hex = match.groupValues[1]
            return "#" + if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        }
        val rgb = rgbColor.matchEntire(color) ?: return null
        val parts = (1..3).map { rgb.groupValues[it].toInt() }
        if (parts.any { it > 255 }) return null
        return "#" + parts.joinToString("") { "%02x".format(it) }
    }

    // La paleta, con los valores del tema claro y del oscuro.
    private val TEXT_COLORS = mapOf(
        "red" to setOf("#f9241c", "#ff5959"), "orange" to setOf("#f98700", "#fd9e1f"),
        "yellow" to setOf("#d3ad53", "#ede508"), "green" to setOf("#389f08", "#16dba5"),
        "blue" to setOf("#2070f7", "#33a7ff"), "pink" to setOf("#e359e1", "#ff7bfd"),
        "purple" to setOf("#8758ff", "#c0a2ff"), "gray" to setOf("#757575", "#9a9a9a"),
    )
    private val HIGHLIGHT_COLORS = mapOf(
        "green" to setOf("#e7ffcd", "#386e53"), "red" to setOf("#fedede", "#7b4242"),
        "orange" to setOf("#ffe4cb", "#744b20"), "blue" to setOf("#d8ecfd", "#22547d"),
        "yellow" to setOf("#fbfbc3", "#757018"), "purple" to setOf("#edddff", "#58486f"),
        "pink" to setOf("#ffe4fb", "#713a66"), "gray" to setOf("#f5f5f5", "#363a41"),
    )

    // Los navegadores ignoran blancos y controles dentro del esquema (`java\tscript:`).
    private val ignoredChars = Regex("[\\s\\u0000-\\u001F]")
    private val dangerousScheme = Regex("^(javascript|vbscript|data):", RegexOption.IGNORE_CASE)

    private fun isDangerous(value: String): Boolean =
        dangerousScheme.containsMatchIn(value.replace(ignoredChars, ""))

    /** Saca href, src y data con esquema peligroso. */
    private fun removeDangerousUrls(body: Element) {
        for (el in body.select("[href],[src],[data]").toList()) {
            val removed = listOf("href", "src", "data").filter { name ->
                (el.hasAttr(name) && isDangerous(el.attr(name))).also { if (it) el.removeAttr(name) }
            }
            when {
                el.normalName() == "a" && "href" in removed && !el.hasAttr("data-note-id") -> el.unwrap()
                el.normalName() == "img" && "src" in removed -> el.remove()
            }
        }
    }
}
