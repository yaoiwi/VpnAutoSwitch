package com.vpnautoswitch.module.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vpnautoswitch.module.ui.hyper.SettingsActionWithArrow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SelfCheckSection(state: AppState) {
    var show by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        SettingsActionWithArrow(
            title = "一键自检",
            icon = MiuixIcons.Info,
            summary = "检查模块、VPN 客户端、网络规则与最近失败",
            onClick = {
                state.refreshDiagnostics()
                show = true
            },
        )
    }
    if (show) {
        SelfCheckDialog(state) { show = false }
    }
}

@Composable
internal fun SelfCheckDialog(state: AppState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val diagnostics = state.diagnostics
    val checks = remember(diagnostics, state.config) {
        DiagnosticsReport.buildChecks(diagnostics, state.config)
    }
    val ok = checks.count { it.status == CheckStatus.OK }
    val warn = checks.count { it.status == CheckStatus.WARN }
    val fail = checks.count { it.status == CheckStatus.FAIL }
    val events = remember(diagnostics.log) {
        diagnostics.log.lines().filter { it.isNotBlank() }.takeLast(6)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = "状态诊断", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "正常 $ok 项 · 注意 $warn 项 · 异常 $fail 项",
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(checks, key = { it.title }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Spacer(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor(item.status)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${item.title} · ${statusLabel(item.status)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = item.detail,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = colors.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "最近事件",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (events.isEmpty()) {
                        Text(
                            text = "暂无事件记录",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    } else {
                        events.forEach { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurfaceVariantSummary,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "重新检测",
                    onClick = {
                        state.refreshDiagnostics()
                        Toast.makeText(context, "已重新检测", Toast.LENGTH_SHORT).show()
                    },
                )
                TextButton(
                    text = "复制报告",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText(
                                "VpnAutoSwitch",
                                DiagnosticsReport.buildText(state.diagnostics, state.config),
                            )
                        )
                        Toast.makeText(context, "报告已复制", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "打开 LSPosed",
                    onClick = {
                        if (!state.openLsposed()) {
                            Toast.makeText(context, "没有找到 LSPosed 管理器", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

private fun statusLabel(status: CheckStatus): String = when (status) {
    CheckStatus.OK -> "正常"
    CheckStatus.WARN -> "注意"
    CheckStatus.FAIL -> "异常"
    CheckStatus.UNKNOWN -> "未知"
}

@Composable
private fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.OK -> Color(0xFF34A853)
    CheckStatus.WARN -> Color(0xFFF5A623)
    CheckStatus.FAIL -> MiuixTheme.colorScheme.error
    CheckStatus.UNKNOWN -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}
