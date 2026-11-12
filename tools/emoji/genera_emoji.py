"""Genera emoji.tsv a partir de emoji-test.txt y las anotaciones en castellano de CLDR.

Uso: python -I genera_emoji.py <carpeta-fuentes> <salida.tsv>
La carpeta de fuentes tiene emoji-test.txt, es.xml y es-derived.xml.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

GRUPOS = {
    "Smileys & Emotion": 0, "People & Body": 0, "Animals & Nature": 1,
    "Food & Drink": 2, "Activities": 3, "Travel & Places": 4,
    "Objects": 5, "Symbols": 6, "Flags": 7,
}
LINEA = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*\S+\s+E[\d.]+\s+(.*)$")


def tono_de_piel(emoji):
    return any(0x1F3FB <= ord(c) <= 0x1F3FF for c in emoji)


def leer_anotaciones(ruta):
    """Devuelve {emoji: (tts, [palabras])}."""
    tts, palabras = {}, {}
    for a in ET.parse(ruta).getroot().iter("annotation"):
        cp, texto = a.get("cp"), (a.text or "").strip()
        if a.get("type") == "tts":
            tts[cp] = texto
        else:
            palabras[cp] = [p.strip() for p in texto.split("|") if p.strip()]
    return tts, palabras


def main():
    fuentes, salida = Path(sys.argv[1]), Path(sys.argv[2])
    anot = [leer_anotaciones(fuentes / "es.xml"), leer_anotaciones(fuentes / "es-derived.xml")]

    def keywords(emoji):
        for clave in (emoji, emoji.replace("️", "")):
            partes = []
            for tts, palabras in anot:
                if clave in tts:
                    partes.append(tts[clave])
                partes.extend(palabras.get(clave, []))
            if partes:
                vistos = []
                for p in partes:
                    if p not in vistos:
                        vistos.append(p)
                return " | ".join(vistos)
        return ""

    filas = {i: [] for i in range(8)}
    grupo = None
    for linea in (fuentes / "emoji-test.txt").read_text(encoding="utf-8").splitlines():
        if linea.startswith("# group:"):
            grupo = linea.split(":", 1)[1].strip()
            continue
        m = LINEA.match(linea)
        if not m or grupo is None or grupo == "Component":
            continue
        emoji = "".join(chr(int(h, 16)) for h in m.group(1).split())
        if tono_de_piel(emoji):
            continue
        filas[GRUPOS[grupo]].append(f"{GRUPOS[grupo]}\t{emoji}\t{m.group(2).strip()}\t{keywords(emoji)}\n")

    salida.parent.mkdir(parents=True, exist_ok=True)
    with open(salida, "w", encoding="utf-8", newline="\n") as f:
        for i in range(8):
            f.writelines(filas[i])
    for i in range(8):
        print(i, len(filas[i]))


main()
