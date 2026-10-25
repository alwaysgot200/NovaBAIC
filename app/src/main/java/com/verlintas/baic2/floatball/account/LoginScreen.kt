/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.floatball.account

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.verlintas.baic2.R
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.floatball.AdvisorPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 好参谋登录门（商业化方案 §6.1）：未登录、或装包/升级后首启（装包登录门）时主界面只出本页。
 *
 * 登录成功回调 [onLoggedIn]；返回键/退出回调 [onExit]（调用方一般 moveTaskToBack）。
 * 注册不在 App 内自建协议——内嵌 WebView 打开站点注册页（[AccountManager.registerUrl]
 * 带 productId=haocanmou，注册后即获好参谋产品角色），注册完成回本页登录。
 * 服务器地址不向用户暴露：由 server-config.properties 按环境构建注入（[AccountApi.SERVER_ORIGIN]）。
 * 错误展示遵循 REASON_* 纪律：此处统一映射 strings.xml 文案。
 */
@Composable
internal fun LoginScreen(
    accountManager: AccountManager,
    onLoggedIn: () -> Unit,
    onExit: () -> Unit,
) {
    var registerShown by remember { mutableStateOf(false) }
    if (registerShown) {
        RegisterWebViewScreen(
            registerUrl = accountManager.registerUrl(),
            onBack = { registerShown = false },
        )
    } else {
        LoginForm(
            accountManager = accountManager,
            onLoggedIn = onLoggedIn,
            onExit = onExit,
            onRegister = { registerShown = true },
        )
    }
}

@Composable
private fun LoginForm(
    accountManager: AccountManager,
    onLoggedIn: () -> Unit,
    onExit: () -> Unit,
    onRegister: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var captchaCode by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf<AccountApi.Captcha?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        captcha = accountManager.fetchCaptcha()
    }

    fun submit() {
        if (busy || username.isBlank() || password.isBlank()) return
        busy = true
        errorRes = null
        errorDetail = null
        scope.launch {
            val captchaEnabled = captcha?.enabled == true
            runCatching {
                accountManager.login(
                    username = username.trim(),
                    password = password,
                    code = captchaCode.takeIf { captchaEnabled && it.isNotBlank() },
                    uuid = captcha?.uuid?.takeIf { captchaEnabled },
                )
            }.onSuccess {
                busy = false
                onLoggedIn()
            }.onFailure { e ->
                busy = false
                val exception = e as? AccountException
                errorRes = when (exception?.reason) {
                    AccountApi.REASON_NETWORK -> R.string.float_account_error_network
                    AccountApi.REASON_BAD_RESPONSE -> R.string.float_account_error_bad_response
                    else -> R.string.float_account_error_generic
                }
                errorDetail = exception?.serverMsg
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_account_login_title),
            onBack = onExit,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Baic2Spacing.xl, vertical = Baic2Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.float_account_login_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.float_account_login_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Baic2Spacing.xs),
                    )

                    Spacer(Modifier.height(Baic2Spacing.xl))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.float_account_username_hint)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.float_account_password_hint)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) IconPasswordHidden else IconPasswordVisible,
                                    contentDescription = stringResource(
                                        if (passwordVisible) {
                                            R.string.float_account_password_hide
                                        } else {
                                            R.string.float_account_password_show
                                        },
                                    ),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Baic2Spacing.md),
                    )

                    if (captcha?.enabled == true) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Baic2Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.md),
                        ) {
                            OutlinedTextField(
                                value = captchaCode,
                                onValueChange = { captchaCode = it },
                                label = { Text(stringResource(R.string.float_account_captcha_hint)) },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            )
                            CaptchaImage(
                                img = captcha?.image.orEmpty(),
                                modifier = Modifier.size(width = 112.dp, height = 56.dp),
                            )
                            TextButton(
                                onClick = { scope.launch { captcha = accountManager.fetchCaptcha() } },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.float_account_captcha_refresh))
                            }
                        }
                    }

                    errorRes?.let { res ->
                        Text(
                            text = if (res == R.string.float_account_error_generic) {
                                stringResource(
                                    R.string.float_account_error_generic,
                                    errorDetail?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.float_account_error_server),
                                )
                            } else {
                                stringResource(res)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                            modifier = Modifier.padding(top = Baic2Spacing.md),
                        )
                    }

                    Button(
                        onClick = { submit() },
                        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Baic2Spacing.xl),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Baic2Spacing.lg),
                                strokeWidth = Baic2Spacing.xxs,
                            )
                            Spacer(Modifier.width(Baic2Spacing.sm))
                            Text(stringResource(R.string.float_account_login_busy))
                        } else {
                            Text(stringResource(R.string.float_account_login_action))
                        }
                    }

                    TextButton(
                        onClick = onRegister,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Baic2Spacing.sm),
                    ) {
                        Text(stringResource(R.string.float_account_register_action))
                    }
                }
            }
        }
    }
}

/**
 * 验证码图片。服务端 svg-captcha 返回 SVG 原文（`<svg` 开头），也可能返回
 * base64 位图——与 Web 端 `toDataUrl` 同款判别：SVG 用 WebView 渲染，
 * 位图在后台线程解码（主线程不做位图解码，AGENTS.md §8 教训 3）。
 */
@Composable
private fun CaptchaImage(img: String, modifier: Modifier = Modifier) {
    if (img.isBlank()) return
    if (img.startsWith("<svg")) {
        // 外层 Box 定死尺寸（AndroidView 内 WebView 会 wrap 测量坍缩），
        // WebView 以 SVG 文档 MIME 直载原文，白底与页面区分开。
        Box(modifier = modifier.background(Color.White)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        // WebView 抢焦点会触发页面自动滚动到验证码行，
                        // 把表单顶出屏——验证码是纯展示，禁掉焦点。
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        // 硬件加速层与 Compose 合成冲突会丢掉同屏其他内容，
                        // 验证码画幅极小，软件层渲染最稳。
                        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(null, img, "image/svg+xml", "utf-8", null)
                },
            )
        }
    } else {
        val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = img) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = Base64.decode(img, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.float_account_captcha_refresh),
                modifier = modifier,
            )
        }
    }
}

/**
 * 注册页（站点后台 SPA 注册路由，内嵌 WebView）。
 * 注册成功后用户返回登录页自行登录（P0 不做注册回跳协议）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RegisterWebViewScreen(
    registerUrl: String,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_account_register_title),
            onBack = onBack,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Text(
                    text = stringResource(R.string.float_account_register_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = Baic2Spacing.lg,
                        vertical = Baic2Spacing.sm,
                    ),
                )
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(registerUrl)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 密码明文切换图标（「点此显示」，线稿眼睛）。
 * 项目只依赖 material-icons-core（无 Visibility/VisibilityOff），这里自绘线稿
 * 矢量而非引 material-icons-extended——避免为两个图标扩大依赖面。
 */
private val IconPasswordVisible: ImageVector by lazy {
    ImageVector.Builder(
        name = "IconPasswordVisible",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(2.5f, 12f)
            curveTo(5.5f, 7.2f, 8.8f, 5f, 12f, 5f)
            curveTo(15.2f, 5f, 18.5f, 7.2f, 21.5f, 12f)
            curveTo(18.5f, 16.8f, 15.2f, 19f, 12f, 19f)
            curveTo(8.8f, 19f, 5.5f, 16.8f, 2.5f, 12f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 虹膜圆（r=3.2，四段 kappa 0.5523 贝塞尔近似）
            moveTo(12f, 8.8f)
            curveTo(13.77f, 8.8f, 15.2f, 10.23f, 15.2f, 12f)
            curveTo(15.2f, 13.77f, 13.77f, 15.2f, 12f, 15.2f)
            curveTo(10.23f, 15.2f, 8.8f, 13.77f, 8.8f, 12f)
            curveTo(8.8f, 10.23f, 10.23f, 8.8f, 12f, 8.8f)
            close()
        }
    }.build()
}

/** 密码明文切换图标（「点此隐藏」，眼睛 + 斜线）。 */
private val IconPasswordHidden: ImageVector by lazy {
    ImageVector.Builder(
        name = "IconPasswordHidden",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(2.5f, 12f)
            curveTo(5.5f, 7.2f, 8.8f, 5f, 12f, 5f)
            curveTo(15.2f, 5f, 18.5f, 7.2f, 21.5f, 12f)
            curveTo(18.5f, 16.8f, 15.2f, 19f, 12f, 19f)
            curveTo(8.8f, 19f, 5.5f, 16.8f, 2.5f, 12f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 8.8f)
            curveTo(13.77f, 8.8f, 15.2f, 10.23f, 15.2f, 12f)
            curveTo(15.2f, 13.77f, 13.77f, 15.2f, 12f, 15.2f)
            curveTo(10.23f, 15.2f, 8.8f, 13.77f, 8.8f, 12f)
            curveTo(8.8f, 10.23f, 10.23f, 8.8f, 12f, 8.8f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 20f)
            lineTo(20f, 4f)
            close()
        }
    }.build()
}
