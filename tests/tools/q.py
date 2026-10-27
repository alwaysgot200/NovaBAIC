# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of BetterAIChat2.
import sqlite3
import sys
from pathlib import Path

db = sys.argv[1] if len(sys.argv) > 1 else str(
    Path(__file__).resolve().parents[2] / 'tests/temp/advisor.db')
sql = sys.argv[2] if len(sys.argv) > 2 else "select name from sqlite_master where type='table'"
conn = sqlite3.connect(db)
cur = conn.cursor()
for row in cur.execute(sql):
    print(row)
conn.close()
