/*
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
 */

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(environment: String, property: String): String? =
    System.getenv(environment) ?: keystoreProperties.getProperty(property)

// 开发环境预置密钥（local.properties 已被 gitignore）：dev.deepseekApiKey=sk-xxx
// 仅注入 debug 构建；release 恒为空串，生产环境不受影响。
val devProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val devDeepseekApiKey: String = devProperties.getProperty("dev.deepseekApiKey") ?: ""
// 升级测试：debug 构建可配 dev.updateManifestUrl=http://.../manifest.json 模拟高版本；
// release 恒空，走 BuildConfig.SERVER_ORIGIN + 生产 manifest。
val devUpdateManifestUrl: String = devProperties.getProperty("dev.updateManifestUrl") ?: ""

// 服务器地址按环境分流（server-config.properties，App 内无运行时入口）：
// server.env（或 -Pbaic.serverEnv）可显式指定 test/prod；留空时按构建类型
// 自动分流——debug 取测试值、release 取生产值，注入 BuildConfig.SERVER_ORIGIN。
val serverConfig = Properties().apply {
    val file = rootProject.file("server-config.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val serverOriginProd: String = serverConfig.getProperty("server.origin.prod")
    ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "https://autojs6.80fafa.com"
// 测试环境地址优先级：-Pbaic.testOrigin > NOVA_TEST_ORIGIN 环境变量 > server-config.properties。
// 真机联调时由 tests/demo/net.ps1 自动注入本机局域网地址（不再依赖 adb reverse）。
val serverOriginTest: String = (providers.gradleProperty("baic.testOrigin").orNull
    ?: System.getenv("NOVA_TEST_ORIGIN")
    ?: serverConfig.getProperty("server.origin.test"))
    ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: serverOriginProd
val serverEnv: String = (providers.gradleProperty("baic.serverEnv").orNull
    ?: serverConfig.getProperty("server.env"))
    ?.trim()?.lowercase().orEmpty()
val serverOriginDebug: String = if (serverEnv == "prod") serverOriginProd else serverOriginTest
val serverOriginRelease: String = if (serverEnv == "test") serverOriginTest else serverOriginProd

android {
    namespace = "com.verlintas.baic2"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.verlintas.baic2"
        minSdk = 26
        targetSdk = 36
        // tag 全驱动：release CI 从 tag 派生 versionName/versionCode 并以
        // -Pbaic.versionName / -Pbaic.versionCode 注入（公式见 release.yml）。
        // 仓库内的值只是本地开发占位，发版不需要先升版本号。
        versionCode = providers.gradleProperty("baic.versionCode").orNull?.toInt()
            ?: 302
        versionName = providers.gradleProperty("baic.versionName").orNull
            ?: "0.3.2"

        // 自更新测试地址：release 恒空（走 SERVER_ORIGIN），debug 由下方覆盖。
        buildConfigField("String", "DEV_UPDATE_MANIFEST_URL", "\"\"")

        // 真机 RPA 夹具（app/src/androidTest）：标准仪器测试通道。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI 按变体分流。AGP 会把 defaultConfig 与 buildType 的 abiFilters 合并
        // 取并集，所以默认只声明发行基线 arm64-v8a；debug 显式补齐模拟器冒烟所需
        // 的 x86_64 与 32 位兼容的 armeabi-v7a，release 不写 ndk 块、严格只打
        // arm64-v8a（v0.3.6 发行包曾因 release 里 clear() 清不掉 defaultConfig
        // 的 x86_64，多打进 67MB 原生库）。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("BAIC2_KEYSTORE_PATH", "storeFile")
            val store = storePath?.let { path ->
                val file = rootProject.file(path)
                if (file.exists()) file else null
            }
            if (store != null) {
                storeFile = store
                storePassword = signingValue("BAIC2_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("BAIC2_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("BAIC2_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_DEEPSEEK_API_KEY", "\"$devDeepseekApiKey\"")
            // 升级测试地址（默认空 -> debug 不参与自动更新）
            buildConfigField("String", "DEV_UPDATE_MANIFEST_URL", "\"$devUpdateManifestUrl\"")
            // 测试环境服务器（server-config.properties）
            buildConfigField("String", "SERVER_ORIGIN", "\"$serverOriginDebug\"")
            // 模拟器冒烟需要 x86_64；显式列全三 ABI，无论 AGP 合并语义是并集
            // 还是覆盖都得到同样的集合。
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        release {
            buildConfigField("String", "DEV_DEEPSEEK_API_KEY", "\"\"")
            // 生产环境服务器（server-config.properties）
            buildConfigField("String", "SERVER_ORIGIN", "\"$serverOriginRelease\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ABI 由 defaultConfig 严格限定为 arm64-v8a（见上），release 不写
            // ndk 块：发行包不再携带 armeabi-v7a / x86_64 的原生库。
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // vendor/RPA：独立 RPA 源码目录（模型 / 存储 / 解释器 / 示例）。
    // 按 AGENTS §4.3 不新增 Gradle 模块，以 sourceSets 编入 :app；
    // vendor/RPA/README.md 记录该模块的边界与数据结构。
    sourceSets {
        getByName("main") {
            // 同时挂到 java/kotlin 两个 source root：AGP 的 java.srcDir 只保证
            // KSP / Java 能看见，Kotlin 编译器的 source set 需显式 kotlin.srcDir。
            val rpaSources = rootProject.file("vendor/RPA/src/main/java")
            java.srcDir(rpaSources)
            kotlin.srcDir(rpaSources)
        }
        // 共享测试夹具：JVM 单测与真机 androidTest 复用同一份 7 步微信脚本。
        // 只存在于测试源集，不进入 release / 发行包。
        val sharedTestSources = file("src/sharedTest/java")
        getByName("test") {
            java.srcDir(sharedTestSources)
            kotlin.srcDir(sharedTestSources)
        }
        getByName("androidTest") {
            java.srcDir(sharedTestSources)
            kotlin.srcDir(sharedTestSources)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Compose 新检查：回调内 context.getString 是浮球包既有写法，
        // 降为 warning，报告可见但不阻塞构建。
        warning += "LocalContextGetResourceValueCall"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:engine"))
    implementation(project(":core:runtime"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:conversations"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:library"))
    implementation(project(":device:api"))
    implementation(project(":device:impl"))

    // assists RPA 基座：opcv 传递 base（AssistsCore/服务合体）；mp 为
    // implementation 不传递，显式引入（投影帧源，模式 B）；log/ime/web/assistsx
    // 仅存档于 vendor/assists submodule，不引入编译
    implementation(libs.assists.opcv)
    implementation(libs.assists.mp)
    implementation(project(":tools"))
    implementation(project(":mcp"))
    implementation(project(":eval"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // 好参谋 AdvisorDatabase：app 模块独立 Room 库（方案 §4，零上游模块改动）
    ksp(libs.room.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    // 真机仪器测试：7 步微信夹具（app/src/androidTest）走标准 AndroidJUnitRunner。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // androidTest 与 JVM 单测一样使用共享夹具；显式引入其编译期依赖，
    // 不依赖 implementation 的传递可见性。
    androidTestImplementation(libs.hilt.android)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
