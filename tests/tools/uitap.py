# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of BetterAIChat2.
"""UI dump 解析（真机复测辅助）：打印带文字的节点中心坐标，便于 adb shell input tap。

用法：python uitap.py ui_home.xml [关键字]
- 不带关键字：打印全部带文字/描述节点（按 y 排序）。
- 带关键字：只打印命中节点，并给出「可点击」中心的建议 tap 坐标。
"""
import re
import sys

raw = open(sys.argv[1], encoding="utf-8", errors="replace").read()
key = sys.argv[2] if len(sys.argv) > 2 else None
rows = []
for m in re.finditer(r"<node[^>]*/?>", raw):
    s = m.group(0)
    t = re.search(r'text="([^"]*)"', s)
    d = re.search(r'content-desc="([^"]*)"', s)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
    if not b:
        continue
    label = (t.group(1) if t else "") or (d.group(1) if d else "")
    if not label:
        continue
    x1, y1, x2, y2 = (int(b.group(i)) for i in (1, 2, 3, 4))
    rows.append((y1, x1, label, (x1 + x2) // 2, (y1 + y2) // 2, x2 - x1, y2 - y1))
rows.sort()
for y1, x1, label, cx, cy, w, h in rows:
    if key and key not in label:
        continue
    print(f"{label!r:36} center=({cx},{cy}) size={w}x{h}")
if key and not any(key in r[2] for r in rows):
    print(f"(no node containing {key!r})")