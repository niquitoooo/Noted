# Datos de emoji

`app/src/main/assets/emoji/emoji.tsv` se genera con `genera_emoji.py` a partir de datos de Unicode (no de ninguna app).

## Fuentes (se bajan a `tools/emoji/fuentes/`, fuera de git)

- `https://unicode.org/Public/emoji/16.0/emoji-test.txt` (Emoji 16.0)
- `https://raw.githubusercontent.com/unicode-org/cldr/release-46/common/annotations/es.xml` (CLDR 46)
- `https://raw.githubusercontent.com/unicode-org/cldr/release-46/common/annotationsDerived/es.xml`, guardado como `es-derived.xml`

## Regenerar

Desde `tools/emoji/`:

    python -I genera_emoji.py fuentes ../../app/src/main/assets/emoji/emoji.tsv

Solo entran los `fully-qualified`, sin el grupo `Component` y sin tonos de piel. Formato por renglón: `categoría<TAB>emoji<TAB>nombre en inglés<TAB>palabras en castellano`.

## Licencia

Los datos de `emoji.tsv` vienen de Unicode (emoji-test.txt, Emoji 16.0) y de las anotaciones CLDR 46, bajo la licencia «UNICODE LICENSE V3». Su texto completo, con el aviso de copyright y de permiso que tiene que acompañar la copia, está en `app/src/main/assets/emoji/LICENSE-UNICODE.txt`.
