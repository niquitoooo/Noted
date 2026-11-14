package com.noted.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lo propio de un cuaderno. Los cuadernos no tienen fila propia: son el nombre que lleva
 * cada nota. Esta tabla solo guarda lo que no entra ahí (el ícono y si está en la papelera),
 * y la fila se borra cuando el cuaderno deja de tener notas.
 */
@Entity(tableName = "notebook")
data class NotebookEntity(
    @PrimaryKey val name: String,
    /** Igual que el de las notas: `emoji:…` o `file:…` (archivo en la carpeta de íconos); "" sin ícono. */
    val icon: String,
    /** Lugar en el menú lateral (se ordena arrastrando). */
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    /**
     * El cuaderno está en la papelera: no aparece en el menú y sus notas no se muestran
     * sueltas en ninguna lista. Las notas no se tocan: siguen con su nombre de cuaderno.
     */
    @ColumnInfo(defaultValue = "0") val trashed: Boolean = false
)
