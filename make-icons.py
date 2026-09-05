"""Genereert de app-iconen voor de webapp uit Adepti/assets/logo.png.

Eenmalig te draaien; de uitkomst staat in overlay/icons/ en gaat mee in git.
Alleen opnieuw nodig als het logo verandert.

    python make-icons.py

Twee soorten:

  icon-192 / icon-512 / apple-touch-icon-180
      Het logo op maat, plat op de navy achtergrond. Ondoorzichtig, vierkant,
      zonder afgeronde hoeken - iOS rondt zelf af en zet een tweede ronding
      over de eerste heen als je het zelf al doet.

  icon-512-maskable
      Android knipt een maskable-icoon bij tot een cirkel, een druppel of een
      afgerond vierkant, afhankelijk van de telefoon. Gegarandeerd zichtbaar
      is alleen de binnenste 80%. Daarom knippen we het gouden zegel uit het
      bronlogo en zetten dat op 82% op een egale plaat - zo blijft de
      buitenring heel, welke vorm het toestel er ook uit snijdt.
"""

import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "OneDrive", "Documenten", "Claude",
                   "Adepti", "assets", "logo.png")
OUT = os.path.join(HERE, "overlay", "icons")

NAVY = (10, 15, 28)          # --navy-deep uit Adepti/index.html
SAFE = 0.82                  # aandeel van de plaat dat het zegel mag vullen
SEAL_R = 0.413               # straal van de gouden ring in het bronlogo (gemeten)
COLORS = 64                  # zie compress()


def flatten(img):
    """Alfa wegwerken op de navy achtergrond."""
    plate = Image.new("RGB", img.size, NAVY)
    plate.paste(img, (0, 0), img)
    return plate


def compress(img):
    """Terugbrengen naar een palet.

    Het logo is een gerenderde plaat met filmkorrel in de achtergrond. Die
    korrel is ruis, en ruis comprimeert niet: als volledige kleurendiepte
    kostte het 512-icoon bijna een halve megabyte, meer dan drie procent van
    de hele bundel. Het zegel gebruikt maar drie kleurfamilies - navy, goud
    en crème - dus 64 kleuren is ruim, en het scheelt een factor drie.
    """
    return img.quantize(colors=COLORS, method=Image.MEDIANCUT)


def plain(src, size):
    return compress(flatten(src.convert("RGBA")).resize((size, size), Image.LANCZOS))


def maskable(src, size):
    """Het zegel uitgeknipt op een egale plaat.

    Eerst probeerden we het hele vierkante logo verkleind in het midden te
    plakken. Dat gaf een zichtbaar vierkant in een vierkant: de achtergrond
    van het logo is lichter en gevlekt, de plaat is egaal navy. Dus knippen we
    de gouden cirkel eruit - gemeten op de middelste beeldrij - en zetten we
    alleen die op de plaat. Wat Android er ook uit knipt, het blijft rond.
    """
    flat = flatten(src.convert("RGBA"))
    cx = cy = flat.width // 2
    r = int(flat.width * SEAL_R)

    mask = Image.new("L", flat.size, 0)
    ImageDraw.Draw(mask).ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)

    inner = int(size * SAFE)
    seal = flat.crop((cx - r, cy - r, cx + r, cy + r)).resize((inner, inner), Image.LANCZOS)
    hole = mask.crop((cx - r, cy - r, cx + r, cy + r)).resize((inner, inner), Image.LANCZOS)

    plate = Image.new("RGB", (size, size), NAVY)
    off = (size - inner) // 2
    plate.paste(seal, (off, off), hole)
    return compress(plate)


def main():
    src = Image.open(SRC)
    os.makedirs(OUT, exist_ok=True)

    targets = [
        ("icon-192.png", plain(src, 192)),
        ("icon-512.png", plain(src, 512)),
        ("apple-touch-icon-180.png", plain(src, 180)),
        ("icon-512-maskable.png", maskable(src, 512)),
    ]

    for name, img in targets:
        path = os.path.join(OUT, name)
        img.save(path, "PNG", optimize=True)
        print("  %-28s %5d x %-5d %6.1f kB"
              % (name, img.width, img.height, os.path.getsize(path) / 1024))


if __name__ == "__main__":
    main()
