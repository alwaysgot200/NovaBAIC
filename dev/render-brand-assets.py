# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of BetterAIChat2.
"""Render the haocanmou brand mark (white feather fan + AI sparkle).

Single source for every rasterised brand asset. The vector geometry here MUST
mirror app/src/main/res/drawable/ic_logo_advisor.xml (Android renders that
file directly for the adaptive icon and the floating ball); this script only
reproduces it for PNG deliverables.

Outputs (regenerated in place):
  BAIC2.png, docs/icon.png, site/assets/icon.png,
  feature/settings/.../drawable-nodpi/ic_brand.png   (600x600 master)
  app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png
  site/assets/og.png                                  (1200x630 banner)

Usage:
    python dev/render-brand-assets.py [--preview]
--preview additionally writes a floating-ball sized proof to tests/tempImg/.
"""

import io
import sys
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent

# --- Geometry (108 viewport, identical to ic_logo_advisor.xml) -------------
HANDLE_PATH = "M51.6,66.4 L56.4,66.4 L56.4,84.2 a2.4,2.4 0 0 1 -4.8,0 Z"
BLADE_PATH = (
    "M46.0,64.1 L18.3,54.0 "
    "Q23.8,48.4 26.4,40.9 "
    "Q33.6,37.9 38.9,32.1 "
    "Q46.8,32.2 54.0,29.0 "
    "Q61.2,32.2 69.1,32.1 "
    "Q74.4,37.9 81.6,40.9 "
    "Q84.2,48.4 89.7,54.0 "
    "L62.0,64.1 Q54.0,68.8 46.0,64.1 Z"
)
GUARD_PATH = "M54,67 L18.3,54.0 M54,67 L89.7,54.0"
RIB_PATH = "M54,67 L38.9,32.1 M54,67 L54,29 M54,67 L69.1,32.1"
STAR_PATH = (
    "M79.5,24.2 L81.2,28.3 L85.3,30.0 L81.2,31.7 "
    "L79.5,35.8 L77.8,31.7 L73.7,30.0 L77.8,28.3 Z"
)

GRADIENT_DEFS = """
  <linearGradient id="blade" x1="54" y1="29" x2="54" y2="69"
      gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#FFFFFF"/>
    <stop offset="1" stop-color="#D0E0F9"/>
  </linearGradient>
  <linearGradient id="handle" x1="54" y1="66" x2="54" y2="87"
      gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#EAC571"/>
    <stop offset="1" stop-color="#B9872F"/>
  </linearGradient>
"""

FOREGROUND_SVG = f"""<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108"
    viewBox="0 0 108 108">
  <defs>{GRADIENT_DEFS}</defs>
  <g fill="none" stroke-linecap="round">
    <path d="{HANDLE_PATH}" fill="url(#handle)"/>
    <path d="{BLADE_PATH}" fill="url(#blade)"/>
    <path d="{GUARD_PATH}" stroke="#F2F8FF" stroke-width="1.5"/>
    <path d="{RIB_PATH}" stroke="#4B86C8" stroke-opacity="0.30"
        stroke-width="1.25"/>
    <circle cx="54" cy="67" r="3.4" fill="#F2CE74" stroke="none"/>
    <circle cx="54" cy="67" r="1.75" fill="#8A5E1C" stroke="none"/>
    <path d="{STAR_PATH}" fill="#9FECFF" stroke="none"/>
  </g>
</svg>"""

BACKGROUND_DEFS = """
  <linearGradient id="bg" x1="0" y1="0" x2="108" y2="108"
      gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#16397F"/>
    <stop offset="0.55" stop-color="#2E5FD0"/>
    <stop offset="1" stop-color="#1E4497"/>
  </linearGradient>
  <radialGradient id="glow" cx="26" cy="14" r="34"
      gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.20"/>
    <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
  </radialGradient>
"""

ICON_SVG = f"""<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108"
    viewBox="0 0 108 108">
  <defs>{BACKGROUND_DEFS}</defs>
  <rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>
  <rect x="0" y="0" width="108" height="108" fill="url(#glow)"/>
</svg>"""


def render(svg: str, width: int, height: int | None = None) -> Image.Image:
    png = cairosvg.svg2png(
        bytestring=svg.encode("utf-8"),
        output_width=width,
        output_height=height if height is not None else width,
    )
    return Image.open(io.BytesIO(png)).convert("RGBA")


def composite_icon(size: int) -> Image.Image:
    """Full-bleed square icon: background plus foreground, supersampled."""
    ss = max(size * 4, 480)
    bg = render(ICON_SVG, ss)
    fg = render(FOREGROUND_SVG, ss)
    bg.alpha_composite(fg)
    return bg.resize((size, size), Image.LANCZOS)


def round_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def write_icon_pngs() -> None:
    master = composite_icon(1200)
    for rel in (
        "BAIC2.png",
        "docs/icon.png",
        "site/assets/icon.png",
        "feature/settings/src/main/res/drawable-nodpi/ic_brand.png",
    ):
        master.resize((600, 600), Image.LANCZOS).save(ROOT / rel)

    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in densities.items():
        base = ROOT / "app/src/main/res" / folder
        square = composite_icon(size)
        square.save(base / "ic_launcher.png")
        rnd = square.copy()
        rnd.putalpha(round_mask(size))
        rnd.save(base / "ic_launcher_round.png")


BANNER_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630"
    viewBox="0 0 1200 630">
  <defs>
    <linearGradient id="bbg" x1="0" y1="0" x2="1200" y2="630"
        gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#16397F"/>
      <stop offset="0.55" stop-color="#2E5FD0"/>
      <stop offset="1" stop-color="#1E4497"/>
    </linearGradient>
    <radialGradient id="bglow" cx="289" cy="82" r="378"
        gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.20"/>
      <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect x="0" y="0" width="1200" height="630" fill="url(#bbg)"/>
  <rect x="0" y="0" width="1200" height="630" fill="url(#bglow)"/>
</svg>"""


def write_banner() -> None:
    banner = render(BANNER_SVG, 1200, 630)

    logo = render(FOREGROUND_SVG, 510)
    # Align the viewport centre (54, 54) with the medallion centre (315, 315).
    banner.alpha_composite(logo, (60, 60))

    draw = ImageDraw.Draw(banner)
    draw.ellipse(
        (315 - 210, 315 - 210, 315 + 210, 315 + 210),
        outline=(255, 255, 255, 42),
        width=3,
    )

    bold = ImageFont.truetype("C:/Windows/Fonts/msyhbd.ttc", 118)
    regular = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 46)
    draw.text((640, 188), "好参谋", font=bold, fill=(255, 255, 255, 255))
    draw.text(
        (645, 350),
        "一键屏幕参谋 · Nova",
        font=regular,
        fill=(216, 230, 255, 236),
    )
    banner.save(ROOT / "site/assets/og.png")


def write_preview() -> None:
    """Proof of the 56dp floating ball at xxhdpi (168px)."""
    out_dir = ROOT / "tests/tempImg"
    out_dir.mkdir(parents=True, exist_ok=True)
    # FloatUi renders the 72dp logo canvas inside the 56dp disc; the canvas is
    # 72/56 larger and centred, so the fan fills ~87% of the visible circle.
    disc_size, logo_size = 1050, round(1050 * 72 / 56)
    offset = (disc_size - logo_size) // 2
    disc = Image.new("RGBA", (disc_size, disc_size), (0, 0, 0, 0))
    ImageDraw.Draw(disc).ellipse(
        (0, 0, disc_size - 1, disc_size - 1), fill=(0x2E, 0x5F, 0xD0, 255)
    )
    logo = render(FOREGROUND_SVG, logo_size)
    disc.alpha_composite(logo, (offset, offset))
    disc.putalpha(round_mask(disc_size))
    disc.resize((168, 168), Image.LANCZOS).save(out_dir / "ball-preview-168.png")


def main() -> None:
    write_icon_pngs()
    write_banner()
    if "--preview" in sys.argv:
        write_preview()
    print("brand assets rendered")


if __name__ == "__main__":
    main()
