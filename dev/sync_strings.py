# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of BetterAIChat2.
"""把 values/strings.xml 里缺失于 values-en/strings.xml 的键补进英文文件。

值先复制中文原文占位（后续人工翻译），保证 CI 的中英键同步检查通过。
用法：在仓库根目录执行 `python dev/sync_strings.py`。
"""
import xml.etree.ElementTree as ET

zh_path = 'app/src/main/res/values/strings.xml'
en_path = 'app/src/main/res/values-en/strings.xml'

zh_tree = ET.parse(zh_path)
zh_root = zh_tree.getroot()
zh_keys = {}
for s in zh_root.findall('string'):
    name = s.get('name')
    if s.get('translatable') == 'false':
        continue
    zh_keys[name] = s.text or ''

en_tree = ET.parse(en_path)
en_root = en_tree.getroot()
en_keys = set(s.get('name') for s in en_root.findall('string'))

missing = [k for k in zh_keys if k not in en_keys]
print(f'missing keys to add: {len(missing)}')

with open(en_path, 'r', encoding='utf-8') as f:
    en_text = f.read()

new_entries = []
for k in missing:
    val = zh_keys[k]
    val = val.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    new_entries.append(f'    <string name="{k}">{val}</string>')

insert_block = '\n' + '\n'.join(new_entries) + '\n'
en_text_new = en_text.replace('</resources>', insert_block + '</resources>')

with open(en_path, 'w', encoding='utf-8') as f:
    f.write(en_text_new)

print(f'added {len(missing)} keys to {en_path}')
