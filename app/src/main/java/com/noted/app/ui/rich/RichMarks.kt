package com.noted.app.ui.rich

import android.text.Spanned
import java.util.concurrent.atomic.AtomicLong

/**
 * Las marcas de formato del campo con formato: spans que dicen qué es cada tramo del texto
 * y que [RichHtml] lee y escribe como el HTML de la nota.
 *
 * Cada span es un objeto nuevo: `Spannable` no admite el mismo objeto dos veces. Para saber
 * si dos marcas dan el mismo formato se usa [sameFormat].
 */
sealed interface RichMark

/** Formato de un tramo dentro del renglón. */
sealed interface InlineMark : RichMark

/**
 * Formato de un renglón entero (título, lista, cita, código, separador), con
 * [BLOCK_MARK_FLAGS] del principio del renglón hasta después de su `\n` (o el final del
 * texto). Un renglón tiene a lo sumo una marca de cada clase; [HeadingMark] y [ListMark]
 * pueden ir juntas (título dentro de un ítem).
 */
sealed interface BlockMark : RichMark

/** Los spans de dibujo: no dicen formato, así que el códec los ignora. */
interface RenderSpan

/** Banderas de las marcas en línea: lo que se escribe al final sigue con el formato. */
const val INLINE_MARK_FLAGS = Spanned.SPAN_EXCLUSIVE_INCLUSIVE

/** Banderas de las marcas de bloque: van de renglón a renglón. */
const val BLOCK_MARK_FLAGS = Spanned.SPAN_PARAGRAPH

/** El único carácter del renglón de un separador. */
const val DIVIDER_CHAR = '\uFFFC'

class BoldMark : InlineMark

class ItalicMark : InlineMark

class UnderlineMark : InlineMark

/** Tachado: `<strike>` (el botón) o `<s>` si se leyó así, para escribirlo igual. */
class StrikeMark(val tag: String = "strike") : InlineMark

class SubscriptMark : InlineMark

class SuperscriptMark : InlineMark

/** Código en línea, `<code spellcheck="false">`. */
class CodeMark : InlineMark

/**
 * Enlace. [attrs] son los atributos del `<a>` menos `href`, para escribirlos igual. Un `<a>`
 * sin `href` (como el `<a data-note-id>`) tiene `href = null` y se escribe sin `href`.
 */
class LinkMark(val href: String?, val attrs: Map<String, String>) : InlineMark

/** Color de texto, `<span class="noted-text-<color>">`. */
class TextColorMark(val color: String) : InlineMark

/** Resaltado, `<span class="noted-highlight-<color>">`; `null` es el verde (`noted-highlight`). */
class HighlightMark(val color: String?) : InlineMark

/** El nombre del resaltado verde en los atajos y en [PendingColor]; se guarda sin sufijo. */
const val GREEN = "green"

/** El resaltado de [color]: el verde es [HighlightMark] con `color = null` (`noted-highlight`). */
fun highlightMark(color: String): HighlightMark = HighlightMark(color.takeIf { it != GREEN })

/** Título `<h1>`…`<h6>`. */
class HeadingMark(val level: Int) : BlockMark

enum class ListKind { Bullet, Ordered, Check }

/**
 * Ítem de lista en el nivel [depth] (0 es el de afuera). [checked] vale para [ListKind.Check];
 * [start] va solo en el primer ítem de un `<ol>` que no empieza en 1.
 */
class ListMark(val kind: ListKind, val depth: Int, val checked: Boolean = false, val start: Int? = null) : BlockMark

/** Renglón de una cita; los renglones con el mismo [group] son una misma `<blockquote>`. */
class QuoteMark(val group: Long) : BlockMark

/** Renglón de un bloque de código; los del mismo [group] son un mismo `<pre>`. */
class CodeBlockMark(val group: Long, val language: String?) : BlockMark

/** Separador `<hr>`, sobre un renglón que tiene solo [DIVIDER_CHAR]. */
class DividerMark : BlockMark

private val blockGroups = AtomicLong()

/**
 * Un `group` nuevo para una cita o un bloque de código. El contador es de toda la app, así
 * dos textos leídos por separado nunca comparten un `group` al unirse.
 */
fun newBlockGroup(): Long = blockGroups.incrementAndGet()

/**
 * Si las dos marcas dan el mismo formato: misma clase y mismos campos. El `when` nombra cada
 * marca y no tiene `else`: una marca nueva no compila hasta decir qué campos compara.
 */
fun RichMark.sameFormat(other: RichMark): Boolean {
    if (this::class != other::class) return false
    return when (this) {
        is BoldMark, is ItalicMark, is UnderlineMark, is SubscriptMark, is SuperscriptMark, is CodeMark -> true
        is StrikeMark -> tag == (other as StrikeMark).tag
        is LinkMark -> href == (other as LinkMark).href && attrs == other.attrs
        is TextColorMark -> color == (other as TextColorMark).color
        is HighlightMark -> color == (other as HighlightMark).color
        is HeadingMark -> level == (other as HeadingMark).level
        is ListMark -> (other as ListMark).let {
            kind == it.kind && depth == it.depth && checked == it.checked && start == it.start
        }
        is QuoteMark -> group == (other as QuoteMark).group
        is CodeBlockMark -> group == (other as CodeBlockMark).group && language == other.language
        is DividerMark -> true
    }
}
