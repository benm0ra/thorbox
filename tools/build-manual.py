#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Builds docs/Clamshell-Beats-Manual.pdf from docs/MANUAL.md (+ the screenshots in docs/img/).

  python3 tools/build-manual.py [--shots DIR]

--shots DIR: refresh docs/img/*.jpg from a folder of CI screenshots first (the ci-shots branch:
`git fetch origin ci-shots && git archive origin/ci-shots | tar -x -C DIR`).

The Markdown is a small subset: # title, ## / ### headings, paragraphs with **bold**, *italic* and
`code`, "- " bullets, | tables |, and ![caption](img/file.jpg) images. Needs reportlab + Pillow.
"""
import argparse, os, re, sys
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, Frame, Image, KeepTogether, PageBreak, PageTemplate, Paragraph,
                                Spacer, Table, TableStyle)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
FONT_DIR = "/usr/share/fonts/truetype/dejavu"

PURPLE = colors.HexColor("#7C4DFF")
GREEN = colors.HexColor("#A3E635")
INK = colors.HexColor("#1B1630")
MUTED = colors.HexColor("#5B5670")
PANEL = colors.HexColor("#F2EFFA")
LINE = colors.HexColor("#D9D3EC")

# Which CI screenshot becomes which manual image, and the part of it to keep (left, top, right, bottom).
# The CI emulator is dual-screen side by side: the top screen is x 0-1180, the deck x 1180-1920.
SHOTS = {
    "sampler.jpg": ("sampler.png", None),
    "wave.jpg": ("wave.png", (180, 0, 1170, 1010)),
    "pattern.jpg": ("wave.png", (300, 250, 1180, 1010)),
    "padedit.jpg": ("padedit-3.png", (180, 0, 1170, 1010)),
    "synth.jpg": ("cat-WARM_PAD.png", (180, 0, 1170, 1010)),
    "drums.jpg": ("drums-dust.png", (180, 0, 1170, 1010)),
    "mixer.jpg": ("MIX.png", (180, 0, 1170, 1010)),
    "look.jpg": ("settings-look.png", (0, 0, 1170, 1060)),
}


def refresh_images(shots):
    from PIL import Image as PImage
    os.makedirs(os.path.join(DOCS, "img"), exist_ok=True)
    for out, (src, box) in SHOTS.items():
        p = os.path.join(shots, src)
        if not os.path.exists(p):
            print(f"  (no {src} in {shots}: keeping the old {out})"); continue
        im = PImage.open(p).convert("RGB")
        if box: im = im.crop(box)
        im.thumbnail((1400, 1400))
        im.save(os.path.join(DOCS, "img", out), quality=86, optimize=True)
        print(f"  img/{out} ← {src}")


def fonts():
    pdfmetrics.registerFont(TTFont("Sans", os.path.join(FONT_DIR, "DejaVuSans.ttf")))
    pdfmetrics.registerFont(TTFont("Sans-Bold", os.path.join(FONT_DIR, "DejaVuSans-Bold.ttf")))
    pdfmetrics.registerFont(TTFont("Sans-Oblique", os.path.join(FONT_DIR, "DejaVuSans-Oblique.ttf")))
    pdfmetrics.registerFont(TTFont("Sans-BoldOblique", os.path.join(FONT_DIR, "DejaVuSans-BoldOblique.ttf")))
    pdfmetrics.registerFont(TTFont("Mono", os.path.join(FONT_DIR, "DejaVuSansMono.ttf")))
    from reportlab.pdfbase.pdfmetrics import registerFontFamily
    registerFontFamily("Sans", normal="Sans", bold="Sans-Bold", italic="Sans-Oblique", boldItalic="Sans-BoldOblique")


def styles():
    def base(**kw):
        d = dict(fontName="Sans", textColor=INK, alignment=TA_LEFT); d.update(kw); return d
    return {
        "title": ParagraphStyle("title", fontName="Sans-Bold", fontSize=30, leading=36, textColor=INK, spaceAfter=10),
        "lead": ParagraphStyle("lead", **base(fontSize=13, leading=19, textColor=MUTED, spaceAfter=10)),
        "h1": ParagraphStyle("h1", fontName="Sans-Bold", fontSize=18, leading=23, textColor=PURPLE, spaceBefore=12, spaceAfter=8, keepWithNext=1),
        "h2": ParagraphStyle("h2", fontName="Sans-Bold", fontSize=12.5, leading=17, textColor=INK, spaceBefore=10, spaceAfter=4, keepWithNext=1),
        "body": ParagraphStyle("body", **base(fontSize=10, leading=14.6, spaceAfter=6)),
        "bullet": ParagraphStyle("bullet", **base(fontSize=10, leading=14.6, leftIndent=14, bulletIndent=3, spaceAfter=3)),
        "cell": ParagraphStyle("cell", **base(fontSize=8.8, leading=12)),
        "cellh": ParagraphStyle("cellh", fontName="Sans-Bold", fontSize=8.8, leading=12, textColor=colors.white),
        "caption": ParagraphStyle("caption", **base(fontSize=8.5, leading=11, textColor=MUTED, spaceBefore=3, spaceAfter=10)),
        "small": ParagraphStyle("small", **base(fontSize=8, leading=11, textColor=MUTED)),
    }


def inline(t):
    t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = re.sub(r"`([^`]+)`", r'<font name="Mono">\1</font>', t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"(?<![*\w])\*([^*]+)\*(?!\*)", r"<i>\1</i>", t)
    return t


def table(rows, st, width):
    head, body = rows[0], rows[1:]
    n = len(head)
    data = [[Paragraph(inline(c), st["cellh"]) for c in head]] + [[Paragraph(inline(c), st["cell"]) for c in r] for r in body]
    first = 0.27 if n == 2 else 0.22
    cw = [width * first] + [width * (1 - first) / (n - 1)] * (n - 1)
    t = Table(data, colWidths=cw, repeatRows=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), PURPLE),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, PANEL]),
        ("LINEBELOW", (0, 0), (-1, -1), 0.4, LINE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("LEFTPADDING", (0, 0), (-1, -1), 6), ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ]))
    return t


def image(path, caption, st, width):
    from PIL import Image as PImage
    p = os.path.join(DOCS, path)
    if not os.path.exists(p):
        return [Paragraph(f"[missing image: {path}]", st["caption"])]
    w, h = PImage.open(p).size
    dw = width
    dh = dw * h / w
    if dh > 92 * mm: dh = 92 * mm; dw = dh * w / h
    return [KeepTogether([Image(p, dw, dh), Paragraph(inline(caption), st["caption"])])]


def parse(md, st, width):
    story, para, rows = [], [], []
    lines = md.splitlines()

    def flush():
        nonlocal para, rows
        if para:
            story.append(Paragraph(inline(" ".join(para)), st["body"])); para = []
        if rows:
            story.append(table(rows, st, width)); story.append(Spacer(1, 8)); rows = []

    first_h1 = True
    for raw in lines:
        line = raw.rstrip()
        if line.startswith("|"):
            if para: flush()
            cells = [c.strip() for c in line.strip("|").split("|")]
            if all(re.fullmatch(r":?-{2,}:?", c) for c in cells): continue
            rows.append(cells); continue
        if rows: flush()
        if not line.strip():
            flush(); continue
        if line.startswith("# "):
            flush(); story.append(Spacer(1, 30 * mm)); story.append(Paragraph(inline(line[2:]), st["title"]))
            story.append(Paragraph("For the alpha builds · rebuilt from docs/MANUAL.md", st["small"])); story.append(Spacer(1, 6 * mm))
            continue
        if line.startswith("## "):
            flush()
            if not first_h1: story.append(Spacer(1, 6))
            first_h1 = False
            story.append(Paragraph(inline(line[3:]), st["h1"])); continue
        if line.startswith("### "):
            flush(); story.append(Paragraph(inline(line[4:]), st["h2"])); continue
        m = re.match(r"!\[(.*)\]\((.*)\)", line.strip())
        if m:
            flush(); story += image(m.group(2), m.group(1), st, width); continue
        if line.startswith("- "):
            flush(); story.append(Paragraph(inline(line[2:]), st["bullet"], bulletText="•")); continue
        para.append(line.strip())
    flush()
    # the cover's lead paragraph: the first body paragraph after the title
    for i, f in enumerate(story):
        if isinstance(f, Paragraph) and f.style.name == "body":
            story[i] = Paragraph(f.text, st["lead"]); break
    return story


def page(canvas, doc):
    w, h = A4
    canvas.saveState()
    canvas.setFillColor(PURPLE); canvas.rect(0, h - 6 * mm, w, 6 * mm, stroke=0, fill=1)
    canvas.setFillColor(GREEN); canvas.rect(0, h - 7.2 * mm, w * 0.33, 1.2 * mm, stroke=0, fill=1)
    canvas.setFont("Sans", 7.5); canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, 10 * mm, "Clamshell Beats · User Manual")
    canvas.drawRightString(w - 18 * mm, 10 * mm, str(doc.page))
    canvas.restoreState()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shots", help="folder of CI screenshots to refresh docs/img from")
    ap.add_argument("--out", default=os.path.join(DOCS, "Clamshell-Beats-Manual.pdf"))
    a = ap.parse_args()
    if a.shots: refresh_images(a.shots)
    fonts()
    st = styles()
    w, h = A4
    margin = 18 * mm
    doc = BaseDocTemplate(a.out, pagesize=A4, leftMargin=margin, rightMargin=margin, topMargin=16 * mm, bottomMargin=18 * mm,
                          title="Clamshell Beats — User Manual", author="Clamshell Beats")
    frame = Frame(margin, 18 * mm, w - 2 * margin, h - 34 * mm, id="f")
    doc.addPageTemplates([PageTemplate(id="p", frames=[frame], onPage=page)])
    md = open(os.path.join(DOCS, "MANUAL.md"), encoding="utf-8").read()
    doc.build(parse(md, st, w - 2 * margin))
    print("wrote", os.path.relpath(a.out, ROOT))


if __name__ == "__main__":
    sys.exit(main())
