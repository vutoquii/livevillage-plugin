#!/usr/bin/env python3
"""Saca de CHANGELOG.md un resumen corto de una version, para pegar en la pagina de
CurseForge/Modrinth/Hangar. Se queda solo con el titulo en negrita de cada bullet (o la
primera frase si no hay negrita) y descarta los parrafos de explicacion: esos documentan
el POR QUE para quien lee el repo, no le sirven a quien esta mirando la pagina de descarga.

Uso: resumir-changelog.py <ruta-al-CHANGELOG.md> <version>
"""
import re
import sys


def extraer(ruta, version):
    lineas = open(ruta, encoding="utf-8").read().splitlines()
    seccion = []
    capturando = False
    for linea in lineas:
        if linea.startswith("## "):
            if capturando:
                break
            if re.match(r"^## " + re.escape(version) + r"(?![0-9.])", linea):
                capturando = True
            continue
        if capturando:
            seccion.append(linea)

    salida = []
    bullet = None

    def volcar():
        nonlocal bullet
        if bullet is None:
            return
        texto = " ".join(bullet.split())
        m = re.match(r"\*\*(.+?)\*\*\.?", texto)
        if m:
            resumen = m.group(1).rstrip(".")
        else:
            partes = re.split(r"(?<=[\w)`\"])\.\s", texto, maxsplit=1)
            resumen = partes[0].rstrip(".")
        salida.append("- " + resumen + ".")
        bullet = None

    for linea in seccion:
        if linea.startswith("### "):
            volcar()
            if salida:
                salida.append("")
            salida.append(linea)
            continue
        if re.match(r"^[-*]\s+", linea):
            volcar()
            bullet = re.sub(r"^[-*]\s+", "", linea)
            continue
        if bullet is not None and linea.strip():
            bullet += " " + linea.strip()
            continue
        volcar()
    volcar()
    return "\n".join(salida).strip()


if __name__ == "__main__":
    ruta, version = sys.argv[1], sys.argv[2]
    texto = extraer(ruta, version)
    if not texto:
        print(f"No encontre '## {version}' en {ruta}", file=sys.stderr)
        sys.exit(1)
    print(texto)
