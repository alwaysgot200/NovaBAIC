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

import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
 * 注册走 App 原生表单（[RegisterScreen]，productId=haocanmou，注册即用），
 * 不再内嵌站点 WebView 页面——WebView 场景下输入法插入点异常（字符倒序）与
 * 软键盘遮挡输入框无法在客户端可靠修复，且截图受限。注册成功后回本页并预填邮箱。
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
    var prefillUsername by remember { mutableStateOf("") }
    if (registerShown) {
        RegisterScreen(
            accountManager = accountManager,
            onBack = { registerShown = false },
            onRegistered = { email ->
                prefillUsername = email
                registerShown = false
            },
        )
    } else {
        LoginForm(
            accountManager = accountManager,
            initialUsername = prefillUsername,
            onLoggedIn = onLoggedIn,
            onExit = onExit,
            onRegister = { registerShown = true },
        )
    }
}

@Composable
private fun LoginForm(
    accountManager: AccountManager,
    initialUsername: String,
    onLoggedIn: () -> Unit,
    onExit: () -> Unit,
    onRegister: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
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
                    .imePadding()
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

/** 注册邮箱格式（与服务端 DTO 一致：6-50 字符的标准邮箱）。 */
private val RegisterEmailRegex = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

private fun isValidRegisterEmail(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.length in 6..50 && RegisterEmailRegex.matches(trimmed)
}

/**
 * 注册密码（服务端 `IsStrongPassword` 默认规则与 `Length(6,20)` 的交集）：
 * 8-20 位，且同时包含大写字母、小写字母与数字。
 */
private fun isStrongRegisterPassword(value: String): Boolean =
    value.length in 8..20 &&
        value.any { it.isUpperCase() } &&
        value.any { it.isLowerCase() } &&
        value.any { it.isDigit() }

/**
 * 原生注册页（替代内嵌站点 WebView 方案）。
 *
 * 四字段：邮箱（即登录账号）、密码、确认密码、图形验证码；昵称/企业名称/联系电话
 * 由服务端按邮箱前缀生成默认值（商业化方案 §5.2 注册即用，productId=haocanmou，
 * 只绑好参谋产品角色、不发激活邮件）。注册成功回登录页并预填邮箱。
 *
 * 输入法与键盘：Compose 原生输入框没有 WebView 的插入点异常（字符从行首倒插），
 * 外层 [imePadding] 在软键盘弹出时把表单整体上移，聚焦输入框始终可见可编辑。
 */
@Composable
private fun RegisterScreen(
    accountManager: AccountManager,
    onBack: () -> Unit,
    onRegistered: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var captchaCode by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf<AccountApi.Captcha?>(null) }
    var captchaLoading by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var registeredEmail by remember { mutableStateOf<String?>(null) }

    suspend fun refreshCaptcha() {
        captchaLoading = true
        captcha = accountManager.fetchCaptcha()
        captchaCode = ""
        codeError = false
        captchaLoading = false
    }

    LaunchedEffect(Unit) { refreshCaptcha() }

    fun validate(): Boolean {
        emailError = !isValidRegisterEmail(email)
        passwordError = !isStrongRegisterPassword(password)
        confirmError = password != confirmPassword
        codeError = captcha?.enabled == true && captchaCode.isBlank()
        return !emailError && !passwordError && !confirmError && !codeError
    }

    fun submit() {
        if (busy || !validate()) return
        busy = true
        errorText = null
        scope.launch {
            runCatching {
                accountManager.register(
                    email = email.trim().lowercase(),
                    password = password,
                    confirmPassword = confirmPassword,
                    code = captchaCode.takeIf { captcha?.enabled == true && it.isNotBlank() },
                    uuid = captcha?.uuid?.takeIf { captcha?.enabled == true },
                )
            }.onSuccess {
                busy = false
                registeredEmail = email.trim().lowercase()
            }.onFailure { e ->
                busy = false
                val exception = e as? AccountException
                errorText = exception?.serverMsg?.takeIf { it.isNotBlank() } ?: context.getString(
                    when (exception?.reason) {
                        AccountApi.REASON_NETWORK -> R.string.float_account_error_network
                        AccountApi.REASON_BAD_RESPONSE -> R.string.float_account_error_bad_response
                        else -> R.string.float_account_error_server
                    },
                )
                // 验证码用后即焚：失败后立刻换一张，避免重复提交已消费的旧码
                refreshCaptcha()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        AdvisorPage(
            title = stringResource(R.string.float_account_register_title),
            onBack = onBack,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Baic2Spacing.xl, vertical = Baic2Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    val doneEmail = registeredEmail
                    if (doneEmail != null) {
                        RegisterSuccessCard(
                            email = doneEmail,
                            onLogin = { onRegistered(doneEmail) },
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.float_account_register_heading),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.float_account_register_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Baic2Spacing.xs),
                        )

                        Spacer(Modifier.height(Baic2Spacing.xl))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = scheme.surface,
                            border = BorderStroke(1.dp, scheme.outlineVariant),
                            shadowElevation = 2.dp,
                        ) {
                            Column(modifier = Modifier.padding(Baic2Spacing.lg)) {
                                OutlinedTextField(
                                    value = email,
                                    onValueChange = {
                                        email = it
                                        emailError = false
                                        errorText = null
                                    },
                                    label = { Text(stringResource(R.string.float_account_register_email_hint)) },
                                    placeholder = {
                                        Text(stringResource(R.string.float_account_register_email_placeholder))
                                    },
                                    singleLine = true,
                                    enabled = !busy,
                                    isError = emailError,
                                    supportingText = if (emailError) {
                                        { Text(stringResource(R.string.float_account_register_error_email)) }
                                    } else {
                                        null
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Email,
                                        imeAction = ImeAction.Next,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = password,
                                    onValueChange = {
                                        password = it
                                        passwordError = false
                                        confirmError = false
                                        errorText = null
                                    },
                                    label = { Text(stringResource(R.string.float_account_password_hint)) },
                                    placeholder = {
                                        Text(stringResource(R.string.float_account_register_password_placeholder))
                                    },
                                    singleLine = true,
                                    enabled = !busy,
                                    isError = passwordError,
                                    visualTransformation = if (passwordVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Next,
                                    ),
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                imageVector = if (passwordVisible) {
                                                    IconPasswordHidden
                                                } else {
                                                    IconPasswordVisible
                                                },
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
                                    supportingText = if (passwordError) {
                                        { Text(stringResource(R.string.float_account_register_error_password)) }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = Baic2Spacing.md),
                                )

                                OutlinedTextField(
                                    value = confirmPassword,
                                    onValueChange = {
                                        confirmPassword = it
                                        confirmError = false
                                        errorText = null
                                    },
                                    label = { Text(stringResource(R.string.float_account_register_confirm_hint)) },
                                    singleLine = true,
                                    enabled = !busy,
                                    isError = confirmError,
                                    visualTransformation = if (confirmVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Next,
                                    ),
                                    trailingIcon = {
                                        IconButton(onClick = { confirmVisible = !confirmVisible }) {
                                            Icon(
                                                imageVector = if (confirmVisible) {
                                                    IconPasswordHidden
                                                } else {
                                                    IconPasswordVisible
                                                },
                                                contentDescription = stringResource(
                                                    if (confirmVisible) {
                                                        R.string.float_account_password_hide
                                                    } else {
                                                        R.string.float_account_password_show
                                                    },
                                                ),
                                            )
                                        }
                                    },
                                    supportingText = if (confirmError) {
                                        { Text(stringResource(R.string.float_account_register_error_confirm)) }
                                    } else {
                                        null
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
                                        horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
                                    ) {
                                        OutlinedTextField(
                                            value = captchaCode,
                                            onValueChange = {
                                                captchaCode = it
                                                codeError = false
                                                errorText = null
                                            },
                                            label = { Text(stringResource(R.string.float_account_captcha_hint)) },
                                            singleLine = true,
                                            enabled = !busy,
                                            isError = codeError,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Done,
                                            ),
                                            keyboardActions = KeyboardActions(onDone = { submit() }),
                                            supportingText = if (codeError) {
                                                { Text(stringResource(R.string.float_account_register_error_code)) }
                                            } else {
                                                null
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        CaptchaBox(
                                            img = captcha?.image.orEmpty(),
                                            loading = captchaLoading,
                                            onRefresh = { scope.launch { refreshCaptcha() } },
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.float_account_register_captcha_tip),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.End)
                                            .padding(top = Baic2Spacing.xs)
                                            .clickable { scope.launch { refreshCaptcha() } },
                                    )
                                }

                                errorText?.let { message ->
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.error,
                                        modifier = Modifier.padding(top = Baic2Spacing.md),
                                    )
                                }

                                Button(
                                    onClick = { submit() },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = Baic2Spacing.lg)
                                        .height(48.dp),
                                ) {
                                    if (busy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(Baic2Spacing.lg),
                                            strokeWidth = Baic2Spacing.xxs,
                                        )
                                        Spacer(Modifier.width(Baic2Spacing.sm))
                                        Text(stringResource(R.string.float_account_register_submit_busy))
                                    } else {
                                        Text(stringResource(R.string.float_account_register_submit))
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Baic2Spacing.md),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.float_account_register_have_account),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onBack, enabled = !busy) {
                                Text(stringResource(R.string.float_account_register_login))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 注册成功卡片：账号已开通（注册即用），一键回登录页并预填邮箱。 */
@Composable
private fun RegisterSuccessCard(
    email: String,
    onLogin: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(Baic2Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(Baic2Spacing.lg))
            Text(
                text = stringResource(R.string.float_account_register_success_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Baic2Spacing.sm))
            Text(
                text = stringResource(R.string.float_account_register_success_body, email),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Baic2Spacing.xl))
            Button(
                onClick = onLogin,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.float_account_register_success_action))
            }
        }
    }
}

/**
 * 验证码图块：白底圆角框，点击即换一张（与登录页同一 [CaptchaImage] 渲染管线）。
 * 图片未就绪/加载中时显示「换一张」，避免出现空白方块。
 */
@Composable
private fun CaptchaBox(
    img: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 104.dp, height = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onRefresh),
        contentAlignment = Alignment.Center,
    ) {
        if (loading || img.isBlank()) {
            Text(
                text = stringResource(R.string.float_account_captcha_refresh),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF576B95),
            )
        } else {
            CaptchaImage(
                img = img,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            )
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
