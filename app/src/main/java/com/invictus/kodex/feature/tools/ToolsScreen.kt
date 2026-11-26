package com.invictus.kodex.feature.tools

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    IconMaker,
}

/** Home for small utilities. Dual-pane on tablets, card list on phones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    onOpenKeystore: () -> Unit,
    onOpenSplitter: () -> Unit,
    onOpenTracer: () -> Unit,
    onOpenIconMaker: () -> Unit,
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ToolCard(
                    icon = XIcons.Key,
                    accent = ToolAccent.Primary,
                    title = R.string.tools_keystore_title,
                    desc = R.string.tools_keystore_desc,
                    isSelected = inTabletMode && selectedTool == ToolSelection.Keystore,
                    onClick = {
                        if (inTabletMode) selectedTool = ToolSelection.Keystore else onOpenKeystore()
                    },
                )
                ToolCard(
                    icon = XIcons.Scissors,
                    accent = ToolAccent.Secondary,
                    title = R.string.tools_split_title,
                    desc = R.string.tools_split_desc,
                    isSelected = inTabletMode && selectedTool == ToolSelection.Splitter,
                    onClick = {
                        if (inTabletMode) selectedTool = ToolSelection.Splitter else onOpenSplitter()
                    },
                )
                ToolCard(
                    icon = XIcons.Image,
                    accent = ToolAccent.Tertiary,
                    title = R.string.tools_tracer_title,
                    desc = R.string.tools_tracer_desc,
                    isSelected = inTabletMode && selectedTool == ToolSelection.Tracer,
                    onClick = {
                        if (inTabletMode) selectedTool = ToolSelection.Tracer else onOpenTracer()
                    },
                )
                ToolCard(
                    icon = XIcons.Palette,
                    accent = ToolAccent.Primary,
                    title = R.string.tools_iconmaker_title,
                    desc = R.string.tools_iconmaker_desc,
                    isSelected = inTabletMode && selectedTool == ToolSelection.IconMaker,
                    onClick = {
                        if (inTabletMode) selectedTool = ToolSelection.IconMaker else onOpenIconMaker()
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
                            ToolSelection.IconMaker -> IconMakerScreen(onBack = onBack)
                        }
                    }
                }
            },
        )
    } else {
        listContent(false)
    }
}

private enum class ToolAccent { Primary, Secondary, Tertiary }

@Composable
private fun ToolCard(
    icon: ImageVector,
    title: Int,
    desc: Int,
    accent: ToolAccent,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (tileBg, tileFg) = when (accent) {
        ToolAccent.Primary -> cs.primaryContainer to cs.onPrimaryContainer
        ToolAccent.Secondary -> cs.secondaryContainer to cs.onSecondaryContainer
        ToolAccent.Tertiary -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    val shape = RoundedCornerShape(28.dp)
    val containerColor = if (isSelected) cs.secondaryContainer else cs.surfaceContainer
    val border = if (isSelected) cs.primary else cs.outlineVariant.copy(alpha = 0.6f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .border(if (isSelected) 2.dp else 1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(tileBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tileFg, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(desc),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (isSelected) cs.primary else cs.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                XIcons.ChevronRight,
                contentDescription = null,
                tint = if (isSelected) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
