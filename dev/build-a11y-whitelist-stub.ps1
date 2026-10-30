<#
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
#>

# build-a11y-whitelist-stub.ps1 —— 构建微信可信白名单「无障碍桩」APK
#
# 【DEPRECATED / 已弃用（2026-10-29 真机 A/B 定案）】本桩与触摸探索两条解锁
# 线均被当日实测推翻：微信 8.0.79（Redmi 23077RABDC / HyperOS OS2.0.205.0 /
# Android 15）运行时的真正放行条件是「启用的无障碍服务列表里存在 TalkBack
# 家族服务（com.google.android.marvin.talkback/*）」——仅装本桩 / 仅开触摸探索
# 都照旧摘树（381 字节空根），而「随选朗读」（触摸探索=false）即 272 节点全树，
# 开↔关在同一微信进程内秒级实时生效（疑被 Tinker 热补丁 patch-5a32c721 改写
# 运行时检查，dex 静态取证的两条件均不成立）。产品改走 app 侧
# WechatTreeUnlock（app/src/main/java/com/verlintas/baic2/floatball/auto/
# WechatTreeUnlock.kt）：分析会话级临时启用「随选朗读」，退出即还原。
# 本脚本仅留作取证记录，不再构建/安装；文内 2026-10-29 各处注释以本横幅为准。
#
# 用途：微信 8.x smartDisableAccessibility 会检查「启用的无障碍服务列表」里
# 是否存在可信白名单组件（精确 ID：org.autojs.autojs/com.stardust.autojs.core
# .accessibility.AccessibilityService）；不存在则摘空自身无障碍节点树。
# 本脚本构建一个 applicationId=org.autojs.autojs 的极小桩 APK，仅声明一个
# 空实现无障碍服务以骗过该检查；服务不处理任何事件、不采集任何数据。
#
# 用法：
#   .\build-a11y-whitelist-stub.ps1           # 仅构建 + apksigner verify
#   .\build-a11y-whitelist-stub.ps1 -Install  # 构建后 adb install -r 到真机
#
# 产物：
#   tests\tempImg\a11y-stub\                  # 中间源码与构建产物（可覆盖重建）
#   tests\tempImg\a11y-whitelist-stub.apk     # 最终签名 APK

[CmdletBinding()]
param(
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

# ---------- 工具链（本机绝对路径） ----------
$SdkRoot       = 'D:\programfiles\Android\SDK'
$BuildToolsDir = Join-Path $SdkRoot 'build-tools\36.0.0'
$AndroidJar    = Join-Path $SdkRoot 'platforms\android-36\android.jar'
$Aapt2         = Join-Path $BuildToolsDir 'aapt2.exe'
$Zipalign      = Join-Path $BuildToolsDir 'zipalign.exe'
$Apksigner     = Join-Path $BuildToolsDir 'apksigner.bat'
$D8            = Join-Path $BuildToolsDir 'd8.bat'
$Javac         = 'D:\programfiles\Java\jdk-21\bin\javac.exe'
$Keystore      = Join-Path $env:USERPROFILE '.android\debug.keystore'
$KeystoreAlias = 'androiddebugkey'
$Adb           = 'adb'
$DeviceSerial  = '449c98301422'

# ---------- 目录（只允许写这两个位置） ----------
$NovaRoot = Split-Path $PSScriptRoot -Parent          # ...\NovaBAIC
$WorkDir  = Join-Path $NovaRoot 'tests\tempImg\a11y-stub'
$OutApk   = Join-Path $NovaRoot 'tests\tempImg\a11y-whitelist-stub.apk'

$ServiceClass = 'com.stardust.autojs.core.accessibility.AccessibilityService'
$StubComponent = "org.autojs.autojs/$ServiceClass"

function Invoke-Tool {
    param(
        [Parameter(Mandatory)][string]$Exe,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$StepName
    )
    Write-Host "====> $StepName"
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "[$StepName] 失败：`"$Exe`" 退出码 $LASTEXITCODE"
    }
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Content
    )
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

try {
    # ---------- 校验工具链 ----------
    foreach ($p in @($Aapt2, $Zipalign, $Apksigner, $D8, $Javac, $AndroidJar, $Keystore)) {
        if (-not (Test-Path $p)) { throw "工具链缺失：$p" }
    }
    if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
        throw "工具链缺失：python（用于向 zip 追加 classes.dex）"
    }

    # ---------- a) 工作目录 + 写入源码/资源 ----------
    Write-Host '====> a) 准备工作目录与源文件'
    if (Test-Path $WorkDir) { Remove-Item -Recurse -Force $WorkDir }
    $ResValuesDir = Join-Path $WorkDir 'res\values'
    $ResXmlDir    = Join-Path $WorkDir 'res\xml'
    $SrcDir       = Join-Path $WorkDir "src\com\stardust\autojs\core\accessibility"
    $ClassesDir   = Join-Path $WorkDir 'classes'
    $DexOutDir    = Join-Path $WorkDir 'dexout'
    New-Item -ItemType Directory -Force -Path $ResValuesDir, $ResXmlDir, $SrcDir, $ClassesDir, $DexOutDir | Out-Null

    $ManifestXml = @'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.autojs.autojs">
    <application
        android:label="@string/stub_app_name"
        android:allowBackup="false">
        <service
            android:name="com.stardust.autojs.core.accessibility.AccessibilityService"
            android:exported="true"
            android:label="@string/stub_app_name"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/stub_a11y_config" />
        </service>
    </application>
</manifest>
'@
    # 与好参谋 app/src/main/res/xml/accessibility_service_config.xml 同构（该配置
    # 在真机可正常解析）。feedbackType 必须含 feedbackSpoken：微信按
    # FEEDBACK_SPOKEN 过滤查询启用服务列表，缺该位则白名单匹配不上。
    # 2026-10-29 真机修正（同日复核作废，以文件头 DEPRECATED 横幅为准）：本块
    # 曾判「触摸探索是真正解锁杠杆」，当日 A/B 定案推翻——真正放行条件是启用
    # 列表存在 TalkBack 家族服务（随选朗读即可），TE 与本桩白名单 ID 均无效。
    # 下述静态 flagRequestTouchExplorationMode 与「启停本桩脉冲触摸探索」方案
    # 一并废弃（该 flag 曾致触摸探索随服务绑定常开、全机触摸卡顿事故），
    # 产品改走 WechatTreeUnlock（会话级临时启用随选朗读，退出还原）。
    $A11yConfigXml = @'
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackSpoken|feedbackGeneric|feedbackHaptic|feedbackAudible|feedbackVisual"
    android:accessibilityFlags="flagDefault|flagRequestTouchExplorationMode"
    android:canRequestTouchExplorationMode="true"
    android:canRetrieveWindowContent="false"
    android:description="@string/stub_a11y_desc"
    android:isAccessibilityTool="true"
    android:notificationTimeout="1000" />
'@
    $StringsXml = @'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="stub_app_name">无障碍兼容组件</string>
    <string name="stub_a11y_desc">为好参谋提供微信等应用的无障碍兼容（微信可信白名单桩）。本组件不处理无障碍事件、不采集任何数据。</string>
</resources>
'@
    $JavaSrc = @'
package com.stardust.autojs.core.accessibility;

import android.view.accessibility.AccessibilityEvent;

/** 微信可信白名单桩服务：仅用于出现在启用无障碍服务列表中，不处理任何事件。 */
public class AccessibilityService extends android.accessibilityservice.AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // no-op
    }

    @Override
    public void onInterrupt() {
        // no-op
    }
}
'@
    Write-Utf8NoBom -Path (Join-Path $WorkDir 'AndroidManifest.xml') -Content $ManifestXml
    Write-Utf8NoBom -Path (Join-Path $ResXmlDir 'stub_a11y_config.xml') -Content $A11yConfigXml
    Write-Utf8NoBom -Path (Join-Path $ResValuesDir 'strings.xml') -Content $StringsXml
    Write-Utf8NoBom -Path (Join-Path $SrcDir 'AccessibilityService.java') -Content $JavaSrc

    # ---------- b) aapt2 compile ----------
    $CompiledRes = Join-Path $WorkDir 'compiled-res.zip'
    Invoke-Tool -Exe $Aapt2 -StepName 'b) aapt2 compile' -Arguments @(
        'compile', '--dir', (Join-Path $WorkDir 'res'), '-o', $CompiledRes
    )

    # ---------- c) javac ----------
    Invoke-Tool -Exe $Javac -StepName 'c) javac' -Arguments @(
        '--release', '11', '-encoding', 'UTF-8', '-cp', $AndroidJar,
        '-d', $ClassesDir, (Join-Path $SrcDir 'AccessibilityService.java')
    )

    # ---------- d) d8 ----------
    Invoke-Tool -Exe $D8 -StepName 'd) d8' -Arguments @(
        '--lib', $AndroidJar, '--min-api', '26', '--output', $DexOutDir,
        (Join-Path $ClassesDir "com\stardust\autojs\core\accessibility\AccessibilityService.class")
    )

    # ---------- e) aapt2 link ----------
    $BaseApk = Join-Path $WorkDir 'base-unsigned.apk'
    Invoke-Tool -Exe $Aapt2 -StepName 'e) aapt2 link' -Arguments @(
        'link', '-o', $BaseApk, '-I', $AndroidJar,
        '--manifest', (Join-Path $WorkDir 'AndroidManifest.xml'),
        '--min-sdk-version', '26', '--target-sdk-version', '36',
        $CompiledRes
    )

    # ---------- f) 追加 classes.dex ----------
    Write-Host '====> f) 追加 classes.dex 进 APK'
    $DexFile = Join-Path $DexOutDir 'classes.dex'
    if (-not (Test-Path $DexFile)) { throw "[f] 失败：未生成 $DexFile" }
    $PyZip = "import zipfile; z=zipfile.ZipFile(r'$($BaseApk -replace '\\','/')','a',zipfile.ZIP_DEFLATED); z.write(r'$($DexFile -replace '\\','/')','classes.dex'); z.close()"
    python -c $PyZip
    if ($LASTEXITCODE -ne 0) { throw "[f] 失败：python 追加 classes.dex 退出码 $LASTEXITCODE" }

    # ---------- g) zipalign ----------
    $AlignedApk = Join-Path $WorkDir 'aligned.apk'
    Invoke-Tool -Exe $Zipalign -StepName 'g) zipalign' -Arguments @(
        '-f', '4', $BaseApk, $AlignedApk
    )

    # ---------- h) apksigner sign ----------
    Invoke-Tool -Exe $Apksigner -StepName 'h) apksigner sign' -Arguments @(
        'sign', '--ks', $Keystore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
        '--ks-key-alias', $KeystoreAlias, '--out', $OutApk, $AlignedApk
    )

    # ---------- i) apksigner verify ----------
    Invoke-Tool -Exe $Apksigner -StepName 'i) apksigner verify' -Arguments @(
        'verify', '--print-certs', $OutApk
    )

    $SizeKB = [math]::Round((Get-Item $OutApk).Length / 1KB, 1)
    Write-Host "构建完成：$OutApk （${SizeKB} KB）"

    # ---------- -Install ----------
    if ($Install) {
        Invoke-Tool -Exe $Adb -StepName 'adb install -r' -Arguments @(
            '-s', $DeviceSerial, 'install', '-r', $OutApk
        )
        Write-Host "已安装到设备 $DeviceSerial，桩组件：$StubComponent"
    }
}
catch {
    Write-Host ("[FATAL] " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
exit 0
