package dev.mayo.ymcl.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mayo.ymcl.app.AppState

/**
 * Missing-Java dialog (P7 §4): shown when a launch cannot find a suitable
 * runtime. Offers auto-install vs manual selection. The install-progress dialog
 * reuses the same P0.2 step ladder (0s → 3s → 10s+cancel → 30s error).
 */
@Composable
fun JavaMissingDialog(state: AppState) {
    val pending = state.pendingJavaInstall ?: return
    if (state.javaInstalling || state.javaInstallError.isNotEmpty()) return
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { state.dismissJavaInstall() },
        title = { Text("缺少 Java 运行时", fontSize = 16.sp) },
        text = {
            Column {
                Text("《${pending.displayName}》需要 Java ${pending.major}，本机没有可用运行时。", fontSize = 13.sp, color = colors.onSurface)
                Spacer(Modifier.height(8.dp))
                Text("可自动安装（约 40MB，镜像优先），或在设置里手动选择。", fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
        },
        confirmButton = {
            PrimaryButton(text = "自动安装", width = 116.dp, onClick = {
                state.requestJavaInstall(pending.instanceId, pending.displayName, pending.major)
            })
        },
        dismissButton = {
            TonalButton(text = "手动选择", width = 108.dp, onClick = {
                state.dismissJavaInstall()
                state.navigate(dev.mayo.ymcl.app.AppPage.SETTINGS)
            })
        },
    )
}

@Composable
fun JavaInstallProgressDialog(state: AppState) {
    if (!state.javaInstalling && state.javaInstallError.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val progress = state.javaInstallProgress
    AlertDialog(
        onDismissRequest = { if (!state.javaInstalling) state.dismissJavaInstall() },
        title = { Text("安装 Java 运行时", fontSize = 16.sp) },
        text = {
            Column {
                if (state.javaInstalling) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(state.javaInstallStage, fontSize = 13.sp, color = colors.onSurface)
                    if (progress.total > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("${progress.bytes / 1024} / ${progress.total / 1024} KB", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                } else {
                    Text(state.javaInstallError, fontSize = 13.sp, color = colors.error)
                }
            }
        },
        confirmButton = {
            if (state.javaInstalling) {
                TonalButton(text = "取消", width = 88.dp, onClick = { state.cancelJavaInstall() })
            } else {
                PrimaryButton(text = "关闭", width = 88.dp, onClick = { state.dismissJavaInstall() })
            }
        },
        dismissButton = null,
    )
}
