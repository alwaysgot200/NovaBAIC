# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of BetterAIChat2.
"""校验 values 与 values-en 两份 strings.xml 的键集合是否一致（CI 键同步检查的本地版）。

输出两侧缺失键的数量与样例；两侧均无缺失即通过。
用法：在仓库根目录执行 `python dev/check_sync.py`。
"""
import xml.etree.ElementTree as ET

zh_tree = ET.parse('app/src/main/res/values/strings.xml')
zh_keys = set(s.get('name') for s in zh_tree.getroot().findall('string') if s.get('translatable') != 'false')

en_tree = ET.parse('app/src/main/res/values-en/strings.xml')
en_keys = set(s.get('name') for s in en_tree.getroot().findall('string'))

missing_en = zh_keys - en_keys
missing_zh = en_keys - zh_keys
print(f'zh={len(zh_keys)} en={len(en_keys)} missing_in_en={len(missing_en)} missing_in_zh={len(missing_zh)}')
if missing_en:
    print('in zh not en:', list(missing_en)[:10])
if missing_zh:
    print('in en not zh:', list(missing_zh)[:10])
