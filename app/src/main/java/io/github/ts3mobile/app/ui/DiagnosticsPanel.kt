package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.service.DiagnosticsSnapshot

@Composable
internal fun DiagnosticsPanel(
    diagnostics: DiagnosticsSnapshot,
    onCopyDiagnostics: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "连接诊断",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "导出内容不包含服务器、密码、昵称或频道名称",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onCopyDiagnostics) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("复制")
            }
        }

        Spacer(Modifier.height(2.dp))
        HorizontalDivider()

        DiagnosticMetric("运行时间", formatDuration(diagnostics.uptimeMs))
        DiagnosticMetric("连接尝试", diagnostics.connectionAttempts.toString())
        DiagnosticMetric("重连尝试", diagnostics.reconnectAttempts.toString())
        DiagnosticMetric("连接成功", diagnostics.successfulConnections.toString())
        DiagnosticMetric("连接失败", diagnostics.failedConnections.toString())
        DiagnosticMetric("可重试失败", diagnostics.retryableFailures.toString())
        DiagnosticMetric("收到语音帧", diagnostics.voiceFramesReceived.toString())
        DiagnosticMetric("丢弃语音帧", diagnostics.voiceFramesDropped.toString())
        DiagnosticMetric("麦克风错误", diagnostics.microphoneErrors.toString())
        DiagnosticMetric("频道切换尝试", diagnostics.channelJoinAttempts.toString())
        DiagnosticMetric("频道切换失败", diagnostics.channelJoinFailures.toString())
        DiagnosticMetric("最近失败类型", diagnostics.lastFailure?.name ?: "NONE")
    }
}

@Composable
private fun DiagnosticMetric(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
