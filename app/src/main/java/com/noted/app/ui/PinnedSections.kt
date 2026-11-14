package com.noted.app.ui

/**
 * Las dos secciones de la lista ("Notas fijadas" y "Todas las notas") y el
 * arrastre entre ellas. Mientras se arrastra, una nota puede pasar a la otra
 * sección (las demás se corren); al soltarla vuelve a la suya, al lugar más
 * cercano. Arrastrar nunca fija ni desfija.
 */
object PinnedSections {

    sealed interface Row<out T> {
        /** Título de sección: [pinned] es la de las fijadas. */
        data class Header(val pinned: Boolean) : Row<Nothing>
        data class Item<T>(val value: T) : Row<T>
    }

    /**
     * Las filas de la lista. Sin fijadas, solo las notas (como siempre). Con
     * fijadas, cada grupo bajo su título; el de las demás solo si hay alguna.
     * [items] ya viene con las fijadas primero.
     */
    fun <T> rows(items: List<T>, pinned: (T) -> Boolean): List<Row<T>> {
        val (top, rest) = items.partition(pinned)
        if (top.isEmpty()) return items.map { Row.Item(it) }
        return buildList {
            add(Row.Header(pinned = true))
            top.forEach { add(Row.Item(it)) }
            if (rest.isNotEmpty()) {
                add(Row.Header(pinned = false))
                rest.forEach { add(Row.Item(it)) }
            }
        }
    }

    /**
     * El orden final al soltar [dragged], que quedó donde está en [dropOrder].
     * La nota se ubica en su sección después de todas sus hermanas que quedaron
     * arriba de ella: dentro de su sección es el lugar donde se soltó; en la
     * otra sección, el borde más cercano de la suya.
     */
    fun <T> settle(dropOrder: List<T>, dragged: T, pinned: (T) -> Boolean): List<T> {
        val at = dropOrder.indexOf(dragged)
        if (at < 0) return dropOrder
        val isPinned = pinned(dragged)
        val siblingsAbove = dropOrder.subList(0, at).count { pinned(it) == isPinned }
        val others = dropOrder.filterIndexed { index, _ -> index != at }
        val own = others.filter { pinned(it) == isPinned }.toMutableList()
        own.add(siblingsAbove, dragged)
        val rest = others.filter { pinned(it) != isPinned }
        return if (isPinned) own + rest else rest + own
    }

    /**
     * La nota que ocupaba en [original] el lugar donde [dragged] queda en
     * [final] (para mover la nota a ese lugar), o null si no se movió.
     */
    fun <T> moveTarget(original: List<T>, final: List<T>, dragged: T): T? {
        val from = original.indexOf(dragged)
        val to = final.indexOf(dragged)
        if (from < 0 || to < 0 || from == to) return null
        return original.getOrNull(to)
    }
}
