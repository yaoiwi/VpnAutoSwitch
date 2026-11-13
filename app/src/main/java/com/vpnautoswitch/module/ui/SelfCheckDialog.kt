package com.vpnautoswitch.module.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 自检弹窗：只放「结论」。
 *
 * 原始日志不在这里列，弹窗底部的「完整日志」把用户带回设置页的日志区——
 * 检查项和日志各归各位，弹窗不会被一大段等宽小字撑开。
 */
@Composable
internal fun SelfCheckDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onOpenLog: () -> Unit,
    onAction: (CheckAction) -> Unit,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val diagnostics = state.diagnostics
    val checks = remember(diagnostics, state.config) {
        DiagnosticsReport.buildChecks(diagnostics, state.config)
    }
    val ok = checks.count { it.status == CheckStatus.OK }
    val warn = checks.count { it.status == CheckStatus.WARN }
    val fail = checks.count { it.status == CheckStatus.FAIL }

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
                            // 只有需要处理的项才给动作：正常项后面挂个按钮反而在催人点
                            if (item.status != CheckStatus.OK) {
                                item.action?.let { action ->
                                    Text(
                                        text = actionLabel(action),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.primary,
                                        modifier = Modifier
                                            .padding(top = 6.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(colors.primary.copy(alpha = 0.10f))
                                            .clickable { onAction(action) }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // 五个按钮排成两行。第一行是「拿报告」的三个动作，第二行是「去修 / 关掉」，
            // 每列等宽，窄屏上也不会互相挤压。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppButton(
                    text = "复制",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText(
                                "VpnAutoSwitch",
                                DiagnosticsReport.buildText(state.diagnostics, state.config),
                            )
                        )
                        Toast.makeText(context, "已复制，日志为最近 60 行", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "分享",
                    onClick = {
                        shareText(
                            context,
                            "VpnAutoSwitch 状态报告",
                            DiagnosticsReport.buildText(state.diagnostics, state.config),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "重新检测",
                    onClick = {
                        state.refreshDiagnostics()
                        Toast.makeText(context, "已重新检测", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            // 两行之间要留空：miuix 的按钮自带高度，紧贴着排会连成一整块
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppButton(
                    text = "完整日志",
                    onClick = {
                        onDismiss()
                        onOpenLog()
                    },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "打开 LSPosed",
                    onClick = {
                        if (!state.openLsposed()) {
                            Toast.makeText(
                                context,
                                "打不开 LSPosed，可能没安装或已被冻结",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun actionLabel(action: CheckAction): String = when (action) {
    CheckAction.OpenVpnSettings -> "去系统设置"
    is CheckAction.OpenAppDetails -> "打开应用信息"
    CheckAction.OpenLsposed -> "打开 LSPosed"
    CheckAction.OpenClientAdapter -> "去客户端适配"
    CheckAction.OpenLog -> "查看日志"
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
