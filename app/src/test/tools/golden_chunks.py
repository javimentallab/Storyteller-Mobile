# -*- coding: utf-8 -*-
"""Rehace los casos de troceo de golden.json con el motor del escritorio (cc_engine.split_chunks).

    python -I golden_chunks.py --engine <Storyteller>/engine [--golden ../resources/golden.json]
           [--big salida.json --epub libro.epub --random 6000]

«chunks» y «chunks_lang» guardan su texto de entrada y se recalcula lo que sale; NEW se añade si
falta. Con --big escribe además, aparte y fuera del repo, los capítulos de un libro y textos
aleatorios con todos los signos: EngineTest los compara si se le pasa -Dgolden.big=<fichero>.
"""
import argparse, importlib.util, json, os, random, re

NEW = [
    "Cuando llegó a la plaza, ya de noche, se sentó en el banco de siempre, sacó el libro y, sin pensarlo mucho, empezó a leer en voz alta.",
    ("uno dos tres cuatro cinco seis siete uno dos tres cuatro cinco seis siete, ocho nueve diez once doce "
     "ocho nueve diez once doce ocho nueve diez once doce, y al final trece catorce quince dieciséis diecisiete "
     "dieciocho diecinueve veinte."),
    ("palabra " * 26).strip() + ", otra cosa más, fin.",
    ("x" * 10 + " ") * 18 + "; y luego sigue un poco más hasta acabar.",
    "palabra " * 70,
    ("Un vendedor virtuoso pregunta cuánto quieres gastar y se apega a esa cantidad; un vendedor que no lo es "
     "te enseña primero lo más caro, te habla de 1.500 dólares como si nada, y cuando ya has dicho que no, "
     "te ofrece lo de 899 como si fuera un regalo, aunque sea justo lo que pensaba venderte desde el principio.\n"
     "—¿Y qué hago? —preguntó ella.\n—Nada, de momento, dijo él."),
]
NEW_LANG = [
    ("zh-cn", "这是一个相当长的句子部分用来测试切分功能是否正常工作，这是一个相当长的句子部分用来测试切分功能是否正常工作，"
              "这是一个相当长的句子部分用来测试切分功能是否正常工作。"),
    ("ru", "Привет, это длинная фраза на русском языке, и она продолжается. Хорошо."),
    ("en", "When he finally reached the square, late at night, he sat down on the usual bench, took out the book, "
           "and, without thinking too much about it, started reading aloud to nobody in particular, as he always did "
           "when the evenings were warm."),
]
# Con números largos: el escritorio mide el trozo con los números escritos en letra y lo vuelve a partir si se
# pasa del tope de XTTS («chunks_spoken»); el móvil hace lo mismo cuando se le pasa con qué escribirlos.
NEW_SPOKEN = [
    ("Esta edición, con ISBN 9788437604947 y depósito legal 12345678, se imprimió en Madrid en el año 1999 por "
     "encargo de la editorial, que la distribuyó en toda España."),
    ("Para pedirlo, llame al 912345678 o al 91-234-56-78, dé el código 978-84-376-0494-7 de la edición del "
     "2026-11-08 y, si no contestan, escriba indicando el pedido 4815162342 y su nombre completo."),
]
# Los códigos se leen cifra a cifra; las fechas, las cantidades redondas y lo demás, como siempre.
NEW_VOICE = [
    ("es", "ISBN 978-84-376-0494-7 y el 912345678."),
    ("es", "El 2026-11-08 ganó 25000000 euros entre 1990-2000."),
]
LANGS = ["es", "es", "es", "en", "fr", "de", "ru", "zh-cn", "ja", "ar", "hi", "pt", "it"]
WORDS = ["casa", "árbol", "Sr.", "EE.", "UU.", "J.", "niño", "pingüino", "3,5", "1.500", "$74.99", "10:30",
         "dijo", "él", "y", "que", "palabra", "larguísima", "Привет", "мир", "这是", "一个", "句子", "猫",
         "Hello", "world", "naïve", "café", "x", "a", "de", "la"]
SIGNS = ["", "", "", "", ",", ",", ";", ":", ".", ".", "?", "!", "…", "...", ".»", "?”", ")", " —", "，", "。",
         "、", "！", "؟", "।", "،"]


def load(engine):
    spec = importlib.util.spec_from_file_location("cc_engine", os.path.join(engine, "cc_engine.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def chunks(E, text, lang="es"):
    E.NUM_LANG = lang
    try:
        return [[c, k] for c, k in E.split_chunks(text)]
    finally:
        E.NUM_LANG = "es"


def random_text(rnd):
    parts = []
    for _ in range(rnd.randint(1, 120)):
        w = rnd.choice(WORDS)
        if rnd.random() < 0.15:
            w = w.capitalize()
        parts.append(w + rnd.choice(SIGNS))
        if rnd.random() < 0.03:
            parts.append("\n")
    return " ".join(parts).replace(" \n ", "\n")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", required=True)
    ap.add_argument("--golden", default=os.path.join(here, "..", "resources", "golden.json"))
    ap.add_argument("--big", default="")
    ap.add_argument("--epub", default="")
    ap.add_argument("--random", type=int, default=6000)
    a = ap.parse_args()
    E = load(a.engine)
    with open(a.golden, encoding="utf-8") as f:
        g = json.load(f)
    have = {c["in"] for c in g["chunks"]}
    g["chunks"] += [{"in": t} for t in NEW if t not in have]
    have = {(c["lang"], c["in"]) for c in g["chunks_lang"]}
    g["chunks_lang"] += [{"lang": l, "in": t} for l, t in NEW_LANG if (l, t) not in have]
    for c in g["chunks"]:
        c["out"] = chunks(E, c["in"])
    for c in g["chunks_lang"]:
        c["out"] = chunks(E, c["in"], c["lang"])
    g["chunks_spoken"] = [{"in": t, "out": chunks(E, t)} for t in NEW_SPOKEN]
    have = {(c["lang"], c["in"]) for c in g["voice"]}
    for lang, t in NEW_VOICE:
        if (lang, t) not in have:
            E.NUM_LANG = lang
            g["voice"].append({"lang": lang, "in": t, "out": E.normalize_text(t)})
            E.NUM_LANG = "es"
    # Cómo escribe num2words cada número de esos casos (y cada cifra): el móvil lo usa en lugar de ICU en la prueba.
    from num2words import num2words
    spell = g.setdefault("spell", {}).setdefault("es", {})
    for t in NEW_SPOKEN + [t for _l, t in NEW_VOICE]:
        for n in {int(x) for x in re.findall(r"\d+", t)} | set(range(10)):
            spell.setdefault(str(n), num2words(n, lang="es"))
    g["pauses"] = dict(E.PAUSES)
    with open(a.golden, "w", encoding="utf-8", newline="\n") as f:
        json.dump(g, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"golden: {len(g['chunks'])} casos en es, {len(g['chunks_lang'])} por idioma")
    if a.big:
        rnd = random.Random(7)
        cases = []
        if a.epub:
            cases += [{"lang": "es", "in": ch["text"]} for ch in E.chapters_from_epub(a.epub)]
        for _ in range(a.random):
            cases.append({"lang": rnd.choice(LANGS), "in": random_text(rnd)})
        for c in cases:
            c["out"] = chunks(E, c["in"], c["lang"])
        with open(a.big, "w", encoding="utf-8", newline="\n") as f:
            json.dump(cases, f, ensure_ascii=False)
        print(f"big: {len(cases)} textos, {sum(len(c['out']) for c in cases)} trozos")


if __name__ == "__main__":
    main()
