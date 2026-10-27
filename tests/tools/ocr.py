# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of BetterAIChat2.
from paddleocr import PaddleOCR
import sys

img = sys.argv[1]
ocr = PaddleOCR(use_angle_cls=False, lang='ch', show_log=False)
result = ocr.ocr(img, cls=False)
for page in result:
    for line in page or []:
        box, (text, score) = line
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        print(f"{text}\t{score:.2f}\tbox=({min(xs):.0f},{min(ys):.0f})-({max(xs):.0f},{max(ys):.0f})\tcenter=({(min(xs)+max(xs))/2:.0f},{(min(ys)+max(ys))/2:.0f})")
