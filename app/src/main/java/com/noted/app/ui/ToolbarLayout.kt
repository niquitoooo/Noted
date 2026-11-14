package com.noted.app.ui

/**
 * Un botón de la barra del editor. [key] es lo que se guarda: no cambia aunque cambie el nombre.
 */
enum class ToolbarItem(val key: String, val label: String) {
    Undo("undo", "Deshacer"),
    Redo("redo", "Rehacer"),
    Bold("bold", "Negrita"),
    Italic("italic", "Cursiva"),
    Strike("strike", "Tachado"),
    Heading("heading", "Título"),
    Bullet("bullet", "Lista"),
    Ordered("ordered", "Lista numerada"),
    Check("check", "Checklist"),
    Quote("quote", "Cita"),
    Toggle("toggle", "Desplegable"),
    Code("code", "Código"),
    Link("link", "Enlace"),
    ChildNote("child_note", "Nota dentro de esta"),
    Divider("divider", "Separador"),
    Attach("attach", "Adjuntar archivo");

    companion object {
        fun ofKey(key: String): ToolbarItem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Cómo se ve la barra: el orden de todos los botones y cuáles están ocultos.
 * el orden se cambia en «Organizar»; además, pedido del usuario, cada
 * botón se puede ocultar y volver a mostrar.
 */
data class ToolbarLayout(val order: List<ToolbarItem>, val hidden: Set<ToolbarItem>) {

    /** Los botones que se ven en la barra, en su orden. */
    val visible: List<ToolbarItem> get() = order.filterNot { it in hidden }

    fun moved(from: Int, to: Int): ToolbarLayout {
        if (from !in order.indices || to !in order.indices || from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun toggled(item: ToolbarItem): ToolbarLayout =
        copy(hidden = if (item in hidden) hidden - item else hidden + item)

    fun orderKeys(): String = order.joinToString(",") { it.key }

    fun hiddenKeys(): String = order.filter { it in hidden }.joinToString(",") { it.key }

    companion object {
        /** De fábrica: todos a la vista; Deshacer y Rehacer primero, después los 14 de siempre. */
        val DEFAULT = ToolbarLayout(ToolbarItem.entries.toList(), emptySet())

        /**
         * Lee lo guardado. Las claves que no se conocen se descartan; los botones que no estaban
         * en lo guardado (nuevos de una versión posterior) van primero.
         */
        fun parse(orderKeys: String?, hiddenKeys: String?): ToolbarLayout {
            val stored = keys(orderKeys).mapNotNull { ToolbarItem.ofKey(it) }.distinct()
            val order = if (stored.isEmpty()) {
                DEFAULT.order
            } else {
                ToolbarItem.entries.filterNot { it in stored } + stored
            }
            val hidden = keys(hiddenKeys).mapNotNull { ToolbarItem.ofKey(it) }.toSet()
            return ToolbarLayout(order, hidden)
        }

        private fun keys(text: String?): List<String> =
            text.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
