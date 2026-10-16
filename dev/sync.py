# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of BetterAIChat2.
#
# BetterAIChat2 is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version.
#
# BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# this program. If not, see <https://www.gnu.org/licenses/>.

"""设备库 <-> 本地 dbdev 同步（adbd root 直推；用法: sync.py pull|push）。"""
import os
import sqlite3
import subprocess
import sys

ADB = r'D:\programfiles\Android\SDK\platform-tools\adb.exe'
PKG = 'com.verlintas.baic2'
BASE = os.path.dirname(os.path.abspath(__file__))
# 二进制调试产物只进 tests/tempImg/dbdev（gitignored），不落 dev/。
DB = os.path.join(BASE, '..', 'tests', 'tempImg', 'dbdev')
FILES = ('baic2.db', 'advisor.db')
AUX = ('baic2.db-wal', 'baic2.db-shm', 'advisor.db-wal', 'advisor.db-shm')


def pull():
    os.makedirs(DB, exist_ok=True)
    for name in FILES + AUX:
        dst = os.path.join(DB, name)
        r = subprocess.run([ADB, 'pull', f'/data/data/{PKG}/databases/{name}', dst],
                           capture_output=True)
        if r.returncode == 0 and os.path.getsize(dst) > 0:
            print('pulled', name, os.path.getsize(dst))
        else:
            if os.path.exists(dst):
                os.remove(dst)


def push():
    # WAL 落盘后主库自足；推主库并删设备 WAL/SHM
    for name in FILES:
        c = sqlite3.connect(os.path.join(DB, name))
        c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        c.commit()
        c.close()
    for name in FILES:
        local = os.path.join(DB, name)
        dst = f'/data/data/{PKG}/databases/{name}'
        subprocess.run([ADB, 'push', local, dst], check=True, capture_output=True)
        print('pushed', name, os.path.getsize(local))
    for name in AUX:
        subprocess.run([ADB, 'shell', 'rm', '-f',
                        f'/data/data/{PKG}/databases/{name}'], check=True,
                       capture_output=True)
    subprocess.run([ADB, 'shell', 'chown', 'u0_a216:u0_a216']
                   + [f'/data/data/{PKG}/databases/{n}' for n in FILES],
                   check=True, capture_output=True)
    subprocess.run([ADB, 'shell', 'chmod', '660']
                   + [f'/data/data/{PKG}/databases/{n}' for n in FILES],
                   check=True, capture_output=True)
    subprocess.run([ADB, 'shell', 'restorecon']
                   + [f'/data/data/{PKG}/databases/{n}' for n in FILES],
                   capture_output=True)
    print('done')


if __name__ == '__main__':
    {'pull': pull, 'push': push}[sys.argv[1]]()
