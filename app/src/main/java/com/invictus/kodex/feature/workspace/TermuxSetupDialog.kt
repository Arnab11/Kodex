package com.invictus.kodex.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.invictus.kodex.R
import com.invictus.kodex.core.external.TermuxLauncher

/** Why the Terminal menu item could not open a Termux session. */
enum class TermuxIssue { NotInstalled, NeedsSetup }

@Composable
fun TermuxSetupDialog(issue: TermuxIssue, onDismiss: () -> Unit) {
    val context = LocalContext.current
    when (issue) {
        TermuxIssue.NotInstalled -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.termux_missing_title)) },
            text = { Text(stringResource(R.string.termux_missing_body)) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { TermuxLauncher.openReleasesPage(context) }
                    onDismiss()
                }) { Text(stringResource(R.string.termux_get)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )

        TermuxIssue.NeedsSetup -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.termux_setup_title)) },
            text = { Text(stringResource(R.string.termux_setup_steps)) },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("termux-setup", TermuxLauncher.SETUP_COMMAND))
                    TermuxLauncher.launch(context)
                    onDismiss()
                }) { Text(stringResource(R.string.termux_copy_and_open)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close_dialog)) } },
        )
    }
}
