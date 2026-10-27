# GitHub build me chalta hai: logo.png se Android ke saare icon banata hai.
# Tumhe isko kuch karna nahi hai.
import os
import sys
from PIL import Image, ImageDraw

RES = "android/app/src/main/res/"
NAVY = (0, 6, 21)

if not os.path.isdir(RES):
    sys.exit("Android res folder nahi mila: " + RES)

sym = Image.open("logo.png").convert("RGB")  # pura logo (labs likha hua)


def canvas(px, bg):
    return Image.new("RGBA", (px, px), bg)


# Dhyan: gol icon me kinare kat jate hain, isliye adaptive icon (fg) me logo chhota (47%) rakha hai.
def place(img, logo, frac):
    # logo (A + flask + "labs") lamba hai, isliye height se fit karte hain
    px = img.size[0]
    h = int(px * frac)
    w = int(h * logo.size[0] / logo.size[1])
    lg = logo.resize((w, h), Image.LANCZOS).convert("RGBA")
    img.alpha_composite(lg, ((px - w) // 2, (px - h) // 2))
    return img


def mask(img, kind, radius_frac=0.22):
    px = img.size[0]
    m = Image.new("L", (px * 4, px * 4), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, px * 4 - 1, px * 4 - 1), radius=int(px * 4 * radius_frac), fill=255)
    img.putalpha(m.resize((px, px), Image.LANCZOS))
    return img


for name, f in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
    d = RES + "mipmap-" + name + "/"
    os.makedirs(d, exist_ok=True)
    legacy, fg = int(48 * f), int(108 * f)
    place(canvas(fg, NAVY + (0,)), sym, 0.47).save(d + "ic_launcher_foreground.png")
    sq = canvas(legacy, NAVY + (255,)); place(sq, sym, 0.78); mask(sq, "square").save(d + "ic_launcher.png")
    rd = canvas(legacy, NAVY + (255,)); place(rd, sym, 0.62); mask(rd, "circle").save(d + "ic_launcher_round.png")

os.makedirs(RES + "values", exist_ok=True)
with open(RES + "values/ic_launcher_background.xml", "w") as fh:
    fh.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
             '    <color name="ic_launcher_background">#000615</color>\n</resources>\n')
print("Icons ban gaye")
