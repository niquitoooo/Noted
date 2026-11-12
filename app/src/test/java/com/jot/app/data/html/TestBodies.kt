package com.jot.app.data.html

/** Cuerpos de prueba compartidos entre los tests del conversor. */
internal object TestBodies {

    /** El cuerpo canónico grande : 40 renglones con todos los bloques de texto. */
    val canonicalMarkdown: String = listOf(
        "# Lista de compras",
        "Texto con **negrita**, *cursiva*, ***las dos***, ~~tachado~~ y `código`.",
        "",
        "## Para la semana",
        "- leche",
        "- pan",
        "  - integral",
        "  - de campo",
        "    - con semillas",
        "- yerba",
        "",
        "### Pendientes",
        "- [ ] llamar a Juan",
        "- [x] pagar la luz",
        "  - [ ] revisar la factura",
        "",
        "#### Pasos",
        "3. prender el horno",
        "4. esperar",
        "  1. diez minutos",
        "5. servir",
        "",
        "> Una cita",
        "> en dos renglones",
        "",
        "```kotlin",
        "val x = 1",
        "",
        "if (a < b && c > d) println(\"ok\")",
        "```",
        "---",
        "##### Enlaces",
        "Ver [la página](https://es.wikipedia.org/wiki/Mate_(bebida)) y [otra](jot://note/7).",
        "\\[nota](jot://note/1)",
        "\\- no es lista",
        "###### Fin",
        "a < b && \"c\" 'd' &amp;",
        "1. uno",
        "2. dos",
        "",
    ).joinToString("\n")
}
