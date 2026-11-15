package com.noted.app.ui

import android.net.Uri

/**
 * Los links de «Acerca de Noted» al repositorio. Los formularios de GitHub
 * (`.github/ISSUE_TEMPLATE/`) se llenan con los parámetros de la dirección: la versión, el
 * teléfono y Android ya van escritos y la persona solo cuenta qué pasó. No se manda nada desde
 * la app: todo se escribe y se envía en GitHub.
 */
object FeedbackLinks {
    const val REPOSITORY = "https://github.com/niquitoooo/Noted"

    fun bug(version: String, phone: String, android: String): String =
        Uri.parse("$REPOSITORY/issues/new").buildUpon()
            .appendQueryParameter("template", "fallo.yml")
            .appendQueryParameter("version", version)
            .appendQueryParameter("telefono", phone)
            .appendQueryParameter("android", android)
            .build()
            .toString()

    fun idea(version: String): String =
        Uri.parse("$REPOSITORY/issues/new").buildUpon()
            .appendQueryParameter("template", "funcion.yml")
            .appendQueryParameter("version", version)
            .build()
            .toString()
}
