package com.invictus.kodex.feature.tools

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.ui.adaptive.AdaptiveTwoPaneLayout
import com.invictus.kodex.ui.adaptive.isTabletLayout
import com.invictus.kodex.ui.icons.XIcons

enum class ToolSelection {
    Keystore,
    Splitter,
    Tracer,
}

/** Home for small utilities. Dual-pane on tablets, card list on phones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    onOpenKeystore: () -> Unit,
    onOpenSplitter: () -> Unit,
    onOpenTracer: () -> Unit,
) {
    val isTablet = isTabletLayout()
    var selectedTool by rememberSaveable { mutableStateOf(ToolSelection.Keystore) }

    val listContent: @Composable (Boolean) -> Unit = { inTabletMode ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.tools_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToolCard(
                    icon = XIcons.Key,
                    title = R.string.tools_keystore_title,
                    desc = R.string.tools_keystore_desc,
                    isSelected = inTabletMode && selectedTool == ToolSelection.Keystore,
                    onClick = {
                        if (inTabletMode) selectedTool = ToolSelection.Keystore else onOpenKeystore()
                    },
                )
                ToolCard(
                    icon = XIcons.Scissors,
                    title = R.string.tools_split_title,
                    desc = R.string.tools_split_desc,
                    isSelected = inTabletMode && selectedTool == ToolSelection.Splitter,
                    onClick = {
                        if (inTabletMode) selectedTool = ToolSelection.Splitter else onOpenSplitter()
                    },
                )
                ToolCard(
                    icon = XIcons.Image,
                    title = R.string.tools_tracer_title,
                    desc = R.string.tools_tracer_desc,
                    isSelected = inTabletMode && selectedTool == ToolSelection.Tracer,
                    onClick = {
                        if (inTabletMode) selectedTool = ToolSelection.Tracer else onOpenTracer()
                    },
                )
            }
        }
    }

    if (isTablet) {
        AdaptiveTwoPaneLayout(
            modifier = Modifier.fillMaxSize(),
            isDualPane = true,
            firstPane = { listContent(true) },
            secondPane = {
                Crossfade(targetState = selectedTool, label = "tools_detail") { tool ->
                    Box(Modifier.fillMaxSize()) {
                        when (tool) {
                            ToolSelection.Keystore -> KeystoreToolScreen(onBack = onBack)
                            ToolSelection.Splitter -> FileSplitterScreen(onBack = onBack)
                            ToolSelection.Tracer -> ImageTracerScreen(onBack = onBack)
                        }
                    }
                }
            },
        )
    } else {
        listContent(false)
    }
}

@Composable
private fun ToolCard(
    icon: ImageVector,
    title: Int,
    desc: Int,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val containerColor = if (isSelected) cs.secondaryContainer else cs.surfaceContainerLow
    val titleColor = if (isSelected) cs.onSecondaryContainer else cs.onSurface
    val descColor = if (isSelected) cs.onSecondaryContainer.copy(alpha = 0.78f) else cs.onSurfaceVariant
    val chevronTint = if (isSelected) cs.onSecondaryContainer.copy(alpha = 0.7f) else cs.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isSelected) cs.primary else cs.primaryContainer),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) cs.onPrimary else cs.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = titleColor)
            Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium, color = descColor)
        }
        Icon(XIcons.ChevronRight, contentDescription = null, tint = chevronTint)
    }
}
