"""Writes the PDF fixtures of PdfTextTest, and their golden file out of the desktop engine.

Run it with the desktop app's translation venv (it has pdfminer.six), from anywhere:

  engine/.venv-translate/Scripts/python -I -B pdf_fixtures.py <cuentacuentos dir> <output dir>

The PDFs are written by hand so they stay small and hold nothing but the text below.
"""
import json
import sys
from pathlib import Path

W, H = 420, 600


def esc(text):
    res = []
    for ch in text:
        # F1 has the ligatures on these two codes, by /Differences
        b = {"ﬁ": 0x80, "ﬂ": 0x81}.get(ch) or ch.encode("cp1252", "replace")[0]
        if b in b"()\\":
            res.append("\\" + chr(b))
        elif b < 32 or b > 126:
            res.append("\\%03o" % b)
        else:
            res.append(chr(b))
    return "".join(res)


def line(font, size, x, y, text):
    return f"BT /{font} {size} Tf {x} {y} Td ({esc(text)}) Tj ET\n"


def para(lines, y, x=50, font="F1", size=11, lead=14):
    s = ""
    for l in lines:
        s += line(font, size, x, y, l)
        y -= lead
    return s, y


def furniture(n):
    """Running head and page number: they repeat in the margins and must go."""
    return line("F3", 8, 150, H - 24, "El faro de la isla") + line("F3", 8, W // 2, 20, str(n))


P1 = ["acia el norte de la isla se alza un faro viejo, de piedra gris y",
      "puerta azul, que nadie ha pintado desde hace muchos años.",
      "Lo cuidaba una mujer llamada Inés, que subía cada tarde los ciento",
      "doce escalones con un candil y un cuaderno de tapas gastadas, donde",
      "anotaba los barcos, el viento y alguna que otra palabra suelta."]
P2 = ["Cuando el invierno cerraba el puerto, Inés se dedicaba a la tradu-",
      "cción de cartas antiguas que le mandaban de tierra firme, y en sus",
      "margenes dejaba dibujos de gaviotas y de redes. Decía que la ﬁesta",
      "del pueblo era la única ﬂor que crecía en aquellas rocas."]
P3 = ["Las noches de tormenta no dormía. Encendía la lámpara, comprobaba",
      "el mecanismo y se sentaba a escuchar cómo el mar golpeaba la base",
      "de la torre. Por la mañana bajaba a recoger lo que las olas traían:",
      "maderas, cuerdas, alguna botella y, una vez, un reloj sin agujas."]
P4 = ["Con los años llegaron otros guardianes, y más tarde las máquinas,",
      "pero el cuaderno siguió en su estante, abierto por la última página.",
      "Los niños del pueblo suben a leerlo en verano y se turnan para",
      "añadir una línea, porque dicen que el faro se apaga si nadie escribe."]
COL_A = ["La primera columna habla", "de la marea, que sube y", "baja dos veces al día y", "deja la playa limpia.",
         "Los pescadores la miran", "antes de salir al mar."]
COL_B = ["La segunda columna habla", "del viento, que llega del", "oeste casi todo el año y", "dobla los pinos del cabo.",
         "Los pastores lo escuchan", "antes de subir al monte."]


def book():
    pages = []
    # 1: heading, drop cap written AFTER its paragraph, hyphen and ligatures
    s = furniture(1) + line("F2", 20, 110, H - 70, "El faro de la isla")
    a, y = para(P1[:2], H - 110, x=82)
    b, y = para(P1[2:], y)
    c, y = para(P2, y - 16)
    s += a + b + c + line("F1", 36, 50, H - 134, "H")
    pages.append({"content": s})
    # 2: plain text
    a, y = para(P3, H - 70)
    b, y = para(P4, y - 16)
    pages.append({"content": furniture(2) + a + b})
    # 3: a chapter whose title comes right before a smaller heading, which stays inside it
    s = furniture(3) + line("F2", 20, 110, H - 70, "Segunda parte") + line("F2", 14, 50, H - 110, "La marea y el viento")
    a, y = para(P3[:2], H - 140)
    pages.append({"content": s + a})
    # 4: two columns
    a, _ = para(COL_A, H - 70, x=40, size=10, lead=13)
    b, _ = para(COL_B, H - 70, x=230, size=10, lead=13)
    pages.append({"content": furniture(4) + a + b})
    # 5: rotated page, with its text the right way up for the reader
    s = ""
    y = 60
    for l in P4:
        s += f"BT /F1 11 Tf 0 1 -1 0 {y} 50 Tm ({esc(l)}) Tj ET\n"
        y += 14
    pages.append({"content": s, "box": [0, 0, H, W], "rotate": 90})
    # 6: a box away from the origin, a stamp up the margin, text in a form (the desktop engine
    #    skips it), and the text operators that move things in other ways
    x0, y0 = 100, 100
    s = f"BT /F3 8 Tf 0 1 -1 0 {x0 + 20} {y0 + 150} Tm (Sello girado en el margen) Tj ET\n"
    s += f"q 1 0 0 1 {x0 + 50} {y0 + 30} cm /Fm1 Do Q\n"
    s += (f"BT /F1 11 Tf 14 TL {x0 + 50} {y0 + H - 80} Td "
          f"[({esc('Con ')}) -300 ({esc('ajustes de separación, ')}) 120 ({esc('escala y demás cosas.')})] TJ T* "
          f"({esc('La segunda línea llega con el salto.')}) Tj "
          f"({esc('La tercera, con la comilla sencilla.')}) ' "
          f"0.5 Tw 95 Tz ({esc('La cuarta va un poco estrecha y separada.')}) ' 100 Tz 0 Tw "
          f"({esc('Una nota')}) ' 5 Ts /F1 7 Tf ({esc(' arriba')}) Tj 0 Ts /F1 11 Tf ({esc(' y sigue.')}) Tj ET\n")
    pages.append({"content": s, "box": [x0, y0, x0 + W, y0 + H], "form": True})
    # 7: an image only, as in a scan
    pages.append({"content": "q 100 0 0 100 100 300 cm /Im1 Do Q\n", "image": True})
    # 8: a glyph whose name nobody knows, which falls back on its code
    s = line("F1", 11, 50, H - 70, "Esta línea tiene un glifo sin nombre aquí:") + \
        "BT /F4 11 Tf 50 " + str(H - 84) + " Td (A) Tj /F1 11 Tf ( y sigue después.) Tj ET\n"
    # and an index line in pieces, in a standard font with /Differences and no /Widths, as Ghostscript
    # writes them: it only lines up with the font's own metrics (40 dots of 0.25 em at 12 points)
    dots = "." * 40
    s += f"BT /F5 12 Tf 50 300 Td ({dots}) Tj 120 0 Td ({dots}) Tj 120 0 Td (Capitulo uno) Tj ET\n"
    pages.append({"content": furniture(8) + s})
    return pages


def build(pages, out):
    objs = {}
    FONTS = 3
    objs[FONTS] = ("<< /F1 << /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding << /BaseEncoding /WinAnsiEncoding "
                   "/Differences [128 /fi /fl] >> >> /F2 << /Type /Font /Subtype /Type1 /BaseFont /Times-Bold "
                   "/Encoding /WinAnsiEncoding >> /F3 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica "
                   "/Encoding /WinAnsiEncoding >> /F4 << /Type /Font /Subtype /Type1 /BaseFont /Times-Roman "
                   "/Encoding << /Differences [65 /nadiesabe] >> >> /F5 << /Type /Font /Subtype /Type1 /BaseFont /Times-Roman "
                   "/Encoding << /Differences [243 /oacute] >> >> >>")
    form_body = line("F3", 9, 0, 0, "Texto dentro de un formulario")
    objs[4] = f"<< /Type /XObject /Subtype /Form /BBox [0 0 300 40] /Resources << /Font {FONTS} 0 R >> /Length {len(form_body)} >>\nstream\n{form_body}endstream"
    objs[5] = "<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Length 1 >>\nstream\n\x80\nendstream"
    next_id = 6
    kids = []
    for p in pages:
        box = p.get("box", [0, 0, W, H])
        content = p["content"]
        c_id, p_id = next_id, next_id + 1
        next_id += 2
        objs[c_id] = f"<< /Length {len(content)} >>\nstream\n{content}endstream"
        res = f"/Font {FONTS} 0 R"
        if p.get("form"):
            res += " /XObject << /Fm1 4 0 R >>"
        if p.get("image"):
            res += " /XObject << /Im1 5 0 R >>"
        rot = f" /Rotate {p['rotate']}" if p.get("rotate") else ""
        objs[p_id] = (f"<< /Type /Page /Parent 2 0 R /MediaBox [{box[0]} {box[1]} {box[2]} {box[3]}]{rot} "
                      f"/Resources << {res} >> /Contents {c_id} 0 R >>")
        kids.append(p_id)
    objs[1] = "<< /Type /Catalog /Pages 2 0 R >>"
    objs[2] = f"<< /Type /Pages /Kids [{' '.join(f'{k} 0 R' for k in kids)}] /Count {len(kids)} >>"
    data = bytearray(b"%PDF-1.4\n")
    offsets = {}
    for i in sorted(objs):
        offsets[i] = len(data)
        data += f"{i} 0 obj\n{objs[i]}\nendobj\n".encode("latin-1")
    xref = len(data)
    n = max(objs) + 1
    data += f"xref\n0 {n}\n0000000000 65535 f \n".encode()
    for i in range(1, n):
        data += b"%010d 00000 n \n" % offsets[i]
    data += f"trailer\n<< /Size {n} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    out.write_bytes(bytes(data))


def dump(engine_dir, path, title):
    """What the desktop engine makes of a PDF: blocks and chapters, nothing translated."""
    sys.path.insert(0, str(engine_dir))
    import cc_translate as cc
    seen = {}
    cc.write_simple_epub = lambda _out, t, lang, chapters: seen.update(title=t, chapters=chapters)
    d = cc.PdfDoc(str(path), "es")
    d.title = title
    d.save("unused.epub")
    return {
        "pages": d.npages,
        "blocks": [{"page": b["page"], "height": b["height"], "top": b["top"], "bottom": b["bottom"],
                    "size": b["size"], "text": b["slot"][0]} for b in d.blocks],
        "chapters": [{"title": c["title"], "paras": [[k, t] for k, t in c["paras"]]} for c in seen["chapters"]],
    }


if __name__ == "__main__":
    root, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    build(book(), out / "sample.pdf")
    build([{"content": "q 100 0 0 100 100 300 cm /Im1 Do Q\n", "image": True}], out / "scan.pdf")
    golden = {"sample": dump(root / "engine", out / "sample.pdf", "Muestra"),
              "scan": dump(root / "engine", out / "scan.pdf", "Escaneado")}
    (out / "sample.golden.json").write_text(json.dumps(golden, ensure_ascii=False, indent=1), encoding="utf-8")
    print("blocks", len(golden["sample"]["blocks"]), "chapters", len(golden["sample"]["chapters"]))
