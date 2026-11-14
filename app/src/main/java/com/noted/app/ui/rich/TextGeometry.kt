package com.noted.app.ui.rich

import android.widget.TextView

/**
 * Dónde está cada renglón y cada posición de un campo de texto, para pasar de un bloque a otro
 * con las flechas y para soltar algo en el texto. Las coordenadas son locales al campo,
 * contando el relleno.
 */
interface TextGeometry {
    val lineCount: Int
    fun lineForY(y: Float): Int
    fun lineForOffset(offset: Int): Int
    fun lineTop(line: Int): Float
    fun lineBottom(line: Int): Float
    fun offsetAt(x: Float, y: Float): Int
    fun horizontal(offset: Int): Float
}

/**
 * La geometría de [view], leída de su `Layout` en cada llamada. Antes del primer layout no hay
 * renglones y todo da 0.
 */
internal class TextViewGeometry(private val view: TextView) : TextGeometry {

    override val lineCount: Int get() = view.layout?.lineCount ?: 0

    override fun lineForY(y: Float): Int {
        val layout = view.layout ?: return 0
        return layout.getLineForVertical((y - view.totalPaddingTop).toInt())
    }

    override fun lineForOffset(offset: Int): Int {
        val layout = view.layout ?: return 0
        return layout.getLineForOffset(offset.coerceIn(0, view.text.length))
    }

    override fun lineTop(line: Int): Float {
        val layout = view.layout ?: return 0f
        return (layout.getLineTop(line.coerceIn(0, layout.lineCount - 1)) + view.totalPaddingTop).toFloat()
    }

    override fun lineBottom(line: Int): Float {
        val layout = view.layout ?: return 0f
        return (layout.getLineBottom(line.coerceIn(0, layout.lineCount - 1)) + view.totalPaddingTop).toFloat()
    }

    override fun offsetAt(x: Float, y: Float): Int {
        val layout = view.layout ?: return 0
        return layout.getOffsetForHorizontal(lineForY(y), x - view.totalPaddingLeft)
    }

    override fun horizontal(offset: Int): Float {
        val layout = view.layout ?: return 0f
        return layout.getPrimaryHorizontal(offset.coerceIn(0, view.text.length)) + view.totalPaddingLeft
    }
}
