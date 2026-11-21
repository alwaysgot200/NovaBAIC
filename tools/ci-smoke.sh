#!/usr/bin/env bash
# 设备冒烟：安装 debug APK → 授予悬浮窗 → 冷启动 → 校验进程存活与 logcat 无崩溃。
# 用法：tools/ci-smoke.sh <apk> <输出目录>（ADB 环境变量可覆盖 adb 路径）
set -euo pipefail

APK="${1:?usage: ci-smoke.sh <apk> <outdir>}"
OUT="${2:-ci-out}"
ADB="${ADB:-adb}"
PKG="com.verlintas.baic2"

mkdir -p "$OUT"
"$ADB" install -r "$APK"
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
"$ADB" shell am force-stop "$PKG" || true
"$ADB" logcat -c || true
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 10

"$ADB" exec-out screencap -p > "$OUT/smoke.png"
"$ADB" logcat -d > "$OUT/logcat.txt"

if ! "$ADB" shell pidof "$PKG" | tr -d '\r' | grep -q '[0-9]'; then
    echo "冒烟失败：应用进程未存活"
    exit 1
fi

if grep -Eq 'FATAL EXCEPTION|AndroidRuntime.*FATAL' "$OUT/logcat.txt"; then
    echo "冒烟失败：logcat 中出现崩溃"
    grep -E -A 20 'FATAL EXCEPTION' "$OUT/logcat.txt" || true
    exit 1
fi

echo "冒烟通过：进程存活，logcat 无 FATAL"
