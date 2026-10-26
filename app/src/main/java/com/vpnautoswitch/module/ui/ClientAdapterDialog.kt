package com.vpnautoswitch.module.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vpnautoswitch.module.apps.AppsRepository
import com.vpnautoswitch.module.system.ExternalControl
import com.vpnautoswitch.module.system.RuntimeDiagnostics
import kotlinx.coroutines.delay
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ClientTest(
    val at: Long = 0L,
    val packageName: String = "",
    val mode: String = "",
    val status: String = "",
    val detail: String = "",
) {
    companion object {
        fun load(context: Context): ClientTest? = try {
            Settings.Secure.getString(
                context.contentResolver,
                RuntimeDiagnostics.CLIENT_TEST_KEY,
            )?.let { raw ->
                JSONObject(raw).let { o ->
                    ClientTest(
                        at = o.optLong("at", 0L),
                        packageName = o.optString("vpnPackage").orEmpty(),
                        mode = o.optString("mode").orEmpty(),
                        status = o.optString("status").orEmpty(),
                        detail = o.optString("detail").orEmpty(),
                    )
                }
            }
        } catch (t: Throwable) {
            null
        }
    }
}

@Composable
internal fun ClientAdapterDialog(state: AppState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val pkg = state.config.vpnPackage
    val inspection = remember(pkg) { AppsRepository.inspectVpn(context, pkg) }
    val test = state.clientTest?.takeIf { it.packageName == pkg }
    val remembered = remember(pkg, test?.at) {
        if (pkg.isEmpty()) null else ExternalControl.modeOfFresh(context, pkg)
    }

    LaunchedEffect(Unit) { state.refreshClientTest() }
    LaunchedEffect(state.clientTest?.status) {
        var attempts = 0
        while (state.clientTest?.status == "running" && attempts < 40) {
            delay(500)
            state.refreshClientTest()
            attempts++
        }
        if (state.clientTest?.status == "running") {
            state.markClientTestTimeout()
        }
    }

    val hasService = inspection?.hasVpnService == true
    val hasExternal = inspection?.hasExternalStart == true

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = "客户端适配", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "检测启动方式并做一次真实测试。测试前请先断开当前 VPN；外部控制可能短暂打开客户端界面。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                item {
                    InfoRow("当前客户端", if (pkg.isEmpty()) "未选择" else state.labelOf(pkg))
                    InfoRow("包名", pkg.ifEmpty { "—" })
                    InfoRow(
                        "VpnService",
                        when {
                            inspection == null -> "无法查询"
                            inspection.hasVpnService -> inspection.vpnServiceName ?: "已声明"
                            else -> "未声明"
                        },
                        inspection?.hasVpnService,
                    )
                    InfoRow(
                        "外部控制入口",
                        if (hasExternal) "可用" else "不可用",
                        hasExternal,
                    )
                    InfoRow(
                        "快捷磁贴",
                        if (inspection?.hasQuickTile == true) "已声明" else "未声明",
                        inspection?.hasQuickTile,
                    )
                    InfoRow(
                        "当前记忆方式",
                        remembered?.let { modeLabel(it) } ?: "未记忆",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "建议：${recommendation(pkg, inspection)}",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "测试结果",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = when (test?.status) {
                            "running" -> "测试中，最多等待 15 秒…"
                            "success" -> "成功：${modeLabel(test.mode)} · ${test.detail}"
                            "failure" -> "失败：${test.detail}"
                            "idle" -> test.detail
                            else -> "尚未测试"
                        },
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = if (test?.status == "failure") {
                            colors.error
                        } else {
                            colors.onSurfaceVariantSummary
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "测试外部控制",
                    onClick = { state.testClient("activity") },
                )
                TextButton(
                    text = "测试 VpnService",
                    onClick = { state.testClient("service") },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = "清除记忆",
                    onClick = { state.forgetClientMode() },
                )
                TextButton(text = "关闭", onClick = onDismiss)
            }
            if (hasExternal && !state.config.allowVpnUi) {
                Text(
                    text = "外部控制可能短暂打开客户端界面；若自动连接仍失败，请在「高级」开启「允许短暂打开 VPN 客户端界面」。",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            if (hasService || hasExternal) {
                Text(
                    text = "提示：测试成功后会记住该方式，实际连接优先使用它；always-on 不在这里测试，避免改写当前常驻 VPN。",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, ok: Boolean? = null) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            color = when (ok) {
                true -> colors.primary
                false -> colors.error
                null -> colors.onSurface
            },
            modifier = Modifier.weight(1.4f),
        )
    }
}

internal fun recommendation(pkg: String, inspection: com.vpnautoswitch.module.apps.VpnClientInspection?): String {
    if (pkg.isEmpty()) return "先选择默认 VPN 客户端"
    if (inspection == null) return "客户端未安装或无法查询，请重新选择"
    if (!inspection.enabled) return "客户端已被禁用，请先在应用信息里启用"
    if (inspection.hasExternalStart) return "优先测试外部控制；失败再测试 VpnService"
    if (inspection.hasVpnService) return "优先使用 always-on；连接失败时测试 VpnService"
    return "客户端没有 VpnService，无法自动连接"
}

private fun modeLabel(mode: String): String = when (mode) {
    "activity" -> "外部控制"
    "service" -> "VpnService"
    else -> mode.ifEmpty { "未记忆" }
}
