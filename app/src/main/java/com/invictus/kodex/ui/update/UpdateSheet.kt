package com.invictus.kodex.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.update.UpdateController
import com.invictus.kodex.core.update.UpdateInfo
import com.invictus.kodex.core.update.UpdateState
import java.util.Locale

/** Shows the update sheet when [UpdateController] asks for it. Put once at the root of the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheetHost() {
    val visible by UpdateController.sheetVisible.collectAsState()
    val state by UpdateController.state.collectAsState()
    val notice by UpdateController.notice.collectAsState()
    if (!visible) return
    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Ready -> s.info
        is UpdateState.Error -> s.info
        else -> null
    } ?: return

    ModalBottomSheet(
        onDismissRequest = { UpdateController.dismissSheet() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(info)
            Notes(info)
            when (val s = state) {
                is UpdateState.Downloading -> DownloadProgress(s)
                is UpdateState.Error -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            Actions(state, info)
        }
    }
}

@Composable
private fun Header(info: UpdateInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    "v" + info.version,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (info.prerelease) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        stringResource(R.string.update_prerelease),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Text(megabytes(info.asset.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Notes(info: UpdateInfo) {
    val text = remember(info.tag) { cleanNotes(info.notes) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        )
    }
}

@Composable
private fun DownloadProgress(s: UpdateState.Downloading) {
    val fraction = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            if (fraction != null) "${(fraction * 100).toInt()}%  ·  ${megabytes(s.done)} / ${megabytes(s.total)}" else megabytes(s.done),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Actions(state: UpdateState, info: UpdateInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is UpdateState.Downloading -> OutlinedButton(
                onClick = { UpdateController.cancelDownload() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.update_cancel)) }
            is UpdateState.Ready -> Button(onClick = { UpdateController.install() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_install))
            }
            is UpdateState.Error -> Button(onClick = { UpdateController.download() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_retry))
            }
            else -> Button(onClick = { UpdateController.download() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_download, megabytes(info.asset.size)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { UpdateController.skipThisVersion() }) { Text(stringResource(R.string.update_skip)) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { UpdateController.dismissSheet() }) { Text(stringResource(R.string.update_later)) }
        }
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes <= 0) "…" else String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

/** Release notes without the markdown noise and without the checksum block. */
internal fun cleanNotes(raw: String): String =
    raw.substringBefore("### SHA-256 checksums")
        .lines()
        .filterNot { it.trim() == "---" || it.trim().startsWith("```") }
        .joinToString("\n") { it.replace(Regex("^#{1,6}\\s*"), "").replace("**", "").replace("`", "") }
        .trim()
        .ifEmpty { "—" }
