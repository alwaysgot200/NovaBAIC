#!/usr/bin/env bash
#
# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# connectedDebugAndroidTest 结果判定。
#
# 真机夹具（微信 / 无障碍 / TalkBack / Shizuku）用 org.junit.Assume.assumeTrue
# 表达「前置不满足即跳过」，但 AGP 在 CI 设备上会把 AssumptionViolatedException
# 写成 <failure> 并让 Gradle 任务失败。本脚本把「假设不成立」还原为跳过，只有
# 真正的断言失败才让作业失败；同时把逐条用例打印到 CI 日志——作业容器结束即删，
# 日志是唯一现场。
#
# 用法：tools/ci-check-androidtest.sh <gradle退出码> [结果目录]
set -uo pipefail

GRADLE_RC="${1:-0}"
RESULTS_DIR="${2:-app/build/outputs/androidTest-results/connected/debug}"

python3 - "$GRADLE_RC" "$RESULTS_DIR" <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET

gradle_rc = int(sys.argv[1])
results_dir = sys.argv[2]

files = sorted(glob.glob(os.path.join(results_dir, "**", "*.xml"), recursive=True))
if not files:
    print(
        f"[androidtest] 未找到测试结果 XML（Gradle 退出码 {gradle_rc}）："
        "设备未连接或 instrumentation 未启动",
        flush=True,
    )
    sys.exit(1)

passed = skipped = failed = 0
failed_cases = []

for path in files:
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        print(f"[androidtest] 结果文件解析失败 {path}: {exc}")
        sys.exit(1)
    for case in tree.iter("testcase"):
        name = f"{case.get('classname', '?')}#{case.get('name', '?')}"
        duration = case.get("time", "?")
        node = case.find("failure")
        if node is None:
            node = case.find("error")
        ignored = case.find("skipped")
        if node is not None:
            detail = node.get("message") or (node.text or "").strip()
            first_line = detail.splitlines()[0] if detail else ""
            if "AssumptionViolatedException" in ((node.text or "") + (node.get("type") or "")):
                skipped += 1
                print(f"[androidtest] SKIP {name} :: {first_line}")
            else:
                failed += 1
                failed_cases.append(name)
                print(f"[androidtest] FAIL {name} :: {first_line}")
                print(node.text or "")
        elif ignored is not None:
            skipped += 1
            print(f"[androidtest] SKIP {name} :: {ignored.get('message', '')}")
        else:
            passed += 1
            print(f"[androidtest] PASS {name} ({duration}s)")

print(f"[androidtest] 合计：通过 {passed}，跳过 {skipped}，失败 {failed}（Gradle 退出码 {gradle_rc}）")
if failed:
    print("[androidtest] 存在真实断言失败：" + "，".join(failed_cases))
    sys.exit(1)
sys.exit(0)
PY
