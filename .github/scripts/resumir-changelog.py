#!/usr/bin/env python3
"""Saca de CHANGELOG.md un resumen corto de una version, para pegar en la pagina de
CurseForge/Modrinth/Hangar. Se queda solo con el titulo en negrita de cada bullet (o la
primera frase si no hay negrita) y descarta los parrafos de explicacion: esos documentan
el POR QUE para quien lee el repo, no le sirven a quien esta mirando la pagina de descarga.

Las secciones "### " cuyo titulo lleva "(interno...)" se saltan ENTERAS: son refactors,
CI o cambios de herramientas que no le sirven a quien solo quiere saber si el mod le va a
funcionar distinto. CHANGELOG.md conserva ese detalle completo para quien lee el repo; este
script solo decide que sale hacia fuera.

Uso: resumir-changelog.py <ruta-al-CHANGELOG.md> <version>
"""
import re
import sys


def extraer(ruta, version):
    """Devuelve (encontrada, texto). encontrada=False solo si la version NO existe en el
    fichero; si existe pero es enteramente interna (ver 0.6.3: solo preparaba el port,
    "Cambiado (interno...)" de principio a fin), encontrada=True y texto="". Hay que
    distinguir los dos casos: lo primero es un error de uso (version mal escrita), lo
    segundo es una version real sin nada que anunciar, y el workflow de publicacion no
    puede fallar por eso."""
    lineas = open(ruta, encoding="utf-8").read().splitlines()
    seccion = []
    capturando = False
    encontrada = False
    for linea in lineas:
        if linea.startswith("## "):
            if capturando:
                break
            if re.match(r"^## " + re.escape(version) + r"(?![0-9.])", linea):
                capturando = True
                encontrada = True
            continue
        if capturando:
            seccion.append(linea)

    # Se agrupa en bloques (titulo, [bullets]) en vez de ir escribiendo linea a linea:
    # asi, al final, un bloque interno o sin bullets se descarta entero de una vez, en
    # lugar de andar recortando texto ya escrito.
    bloques = [(None, [])]  # el primer bloque son los bullets sueltos antes del primer "###"
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
        bloques[-1][1].append(resumen)
        bullet = None

    for linea in seccion:
        if linea.startswith("### "):
            volcar()
            bloques.append((linea, []))
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

    salida = []
    for titulo, bullets in bloques:
        # Secciones marcadas "(interno...)" y bloques sin ningun bullet (por ejemplo
        # porque todos sus bullets eran internos a nivel de linea) se descartan enteros:
        # una cabecera sin nada debajo no le dice nada a quien lee el resumen.
        if not bullets:
            continue
        if titulo and "(interno" in titulo.lower():
            continue
        if salida:
            salida.append("")
        if titulo:
            salida.append(titulo)
        for r in bullets:
            salida.append("- " + r + ".")
    return encontrada, "\n".join(salida).strip()


if __name__ == "__main__":
    ruta, version = sys.argv[1], sys.argv[2]
    encontrada, texto = extraer(ruta, version)
    if not encontrada:
        print(f"No encontre '## {version}' en {ruta}", file=sys.stderr)
        sys.exit(1)
    if not texto:
        # Version real (ver el docstring de extraer) sin nada de cara al usuario. No es un
        # error: que la publicacion siga con un changelog corto en vez de romperse.
        print("- Cambios internos; sin novedades para el uso del mod.")
    else:
        print(texto)
