package com.invictus.kodex.feature.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.iconmaker.BgKind
import com.invictus.kodex.core.iconmaker.GlyphKind
import com.invictus.kodex.core.iconmaker.IconShape
import com.invictus.kodex.core.iconmaker.IconSpec
import com.invictus.kodex.core.iconmaker.SymbolLibrary
import com.invictus.kodex.ui.icons.XIcons

private const val GRID_COLUMNS = 6

private val SUGGESTED = listOf(
    "bolt", "home", "favorite", "star", "code", "terminal", "folder", "photo-camera", "music-note", "mail",
    "chat", "shopping-cart", "settings", "search", "person", "group", "map", "rocket-launch", "sports-esports",
    "videocam", "image", "edit", "calendar-month", "alarm", "wifi", "bluetooth", "key", "shield", "download",
    "upload", "sync", "notifications", "bookmark", "flag", "lightbulb", "school", "store", "restaurant",
    "flight", "directions-car", "fitness-center", "water-drop", "diamond", "psychology", "smart-toy", "memory",
    "build", "construction", "cleaning-services", "local-fire-department", "cloud", "lock", "pets", "spa",
)

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF7C4DFF, 0xFF3D2BC4, 0xFF2563EB, 0xFF0EA5E9, 0xFF10B981, 0xFF84CC16,
    0xFFF5A524, 0xFFF97316, 0xFFEF4444, 0xFFEC4899, 0xFF64748B, 0xFF1E293B,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconMakerScreen(
    onBack: () -> Unit,
    viewModel: IconMakerViewModel = viewModel(factory = IconMakerViewModel.Factory),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val spec = state.spec
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.effects.collect { snackbar.showSnackbar(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.setPicture(uri)
    }
    var mode by rememberSaveable { mutableStateOf(PreviewMode.Launcher) }
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_iconmaker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = viewModel::saveZip, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.im_save_zip), maxLines = 1)
                    }
                    Button(onClick = viewModel::requestApply, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.im_apply), maxLines = 1)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Pinned: the preview stays visible while you edit below it.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (m in PreviewMode.entries) {
                    FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(previewModeLabel(m)) })
                }
            }
            IconPreview(mode = mode, layers = state.layers, shape = spec.legacyShape, onShape = { s -> viewModel.edit { copy(legacyShape = s) } })
            HorizontalDivider()

            val symbols = state.symbols
            val rows = remember(symbols, query) { symbolRows(symbols, query) }
            LazyColumn(Modifier.weight(1f).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                item(key = "src") {
                    Section(R.string.im_section_source) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SourceChip(GlyphKind.Symbol, R.string.im_src_symbol, spec) { viewModel.edit { copy(kind = GlyphKind.Symbol) } }
                            SourceChip(GlyphKind.Text, R.string.im_src_text, spec) { viewModel.edit { copy(kind = GlyphKind.Text) } }
                            SourceChip(GlyphKind.Picture, R.string.im_src_image, spec) {
                                if (state.hasPicture) viewModel.edit { copy(kind = GlyphKind.Picture) } else picker.launch("image/*")
                            }
                        }
                    }
                }
                when (spec.kind) {
                    GlyphKind.Symbol -> {
                        item(key = "search") {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.im_search_hint, symbols.size)) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                        if (state.symbolsLoading) {
                            item(key = "loading") { Hint(R.string.im_loading_icons) }
                        } else if (rows.isEmpty()) {
                            item(key = "none") { Hint(R.string.im_no_icons) }
                        }
                        items(rows, key = { it.first().name }) { row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                for (i in 0 until GRID_COLUMNS) {
                                    val s = row.getOrNull(i)
                                    if (s != null) {
                                        SymbolCell(
                                            name = s.name,
                                            selected = s.name == spec.symbol,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        ) { viewModel.edit { copy(symbol = s.name) } }
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    GlyphKind.Text -> item(key = "text") {
                        OutlinedTextField(
                            value = spec.text,
                            onValueChange = { v -> viewModel.edit { copy(text = v.take(3)) } },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.im_text_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    GlyphKind.Picture -> item(key = "picture") {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { picker.launch("image/*") }) {
                                Text(stringResource(if (state.hasPicture) R.string.im_change_image else R.string.im_pick_image))
                            }
                            SwitchRow(R.string.im_tint_image, spec.tintPicture) { viewModel.edit { copy(tintPicture = it) } }
                        }
                    }
                }
                item(key = "fg") {
                    Section(R.string.im_section_fg) {
                        ColorEditor(spec.fgColor) { c -> viewModel.edit { copy(fgColor = c) } }
                    }
                }
                item(key = "bg") {
                    Section(R.string.im_section_bg) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = spec.bgKind == BgKind.Solid,
                                onClick = { viewModel.edit { copy(bgKind = BgKind.Solid) } },
                                label = { Text(stringResource(R.string.im_bg_solid)) },
                            )
                            FilterChip(
                                selected = spec.bgKind == BgKind.Gradient,
                                onClick = { viewModel.edit { copy(bgKind = BgKind.Gradient) } },
                                label = { Text(stringResource(R.string.im_bg_gradient)) },
                            )
                        }
                        Label(R.string.im_bg_color)
                        ColorEditor(spec.bg1) { c -> viewModel.edit { copy(bg1 = c) } }
                        if (spec.bgKind == BgKind.Gradient) {
                            Label(R.string.im_bg_color2)
                            ColorEditor(spec.bg2) { c -> viewModel.edit { copy(bg2 = c) } }
                            Text(stringResource(R.string.im_bg_angle, spec.bgAngle), style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = spec.bgAngle / 45f,
                                onValueChange = { v -> viewModel.edit { copy(bgAngle = (v.toInt() * 45) % 360) } },
                                valueRange = 0f..7f,
                                steps = 6,
                            )
                        }
                    }
                }
                item(key = "layout") {
                    Section(R.string.im_section_layout) {
                        PercentSlider(R.string.im_size, spec.sizePct, 30..130) { viewModel.edit { copy(sizePct = it) } }
                        PercentSlider(R.string.im_offset_x, spec.offsetXPct, -30..30) { viewModel.edit { copy(offsetXPct = it) } }
                        PercentSlider(R.string.im_offset_y, spec.offsetYPct, -30..30) { viewModel.edit { copy(offsetYPct = it) } }
                        SwitchRow(R.string.im_shadow, spec.shadow) { viewModel.edit { copy(shadow = it) } }
                    }
                }
            }
        }
    }

    state.targets?.let { targets ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTargets,
            title = { Text(stringResource(R.string.im_pick_project)) },
            text = {
                Column {
                    for (t in targets) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { viewModel.applyTo(t) }.padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                            Text(t.root.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissTargets) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Suggested icons first when the search is empty; otherwise name matches, prefix matches first. */
private fun symbolRows(all: List<SymbolLibrary.Symbol>, query: String): List<List<SymbolLibrary.Symbol>> {
    val q = query.trim().lowercase().replace(' ', '-')
    val list = if (q.isEmpty()) {
        val byName = all.associateBy { it.name }
        val first = SUGGESTED.mapNotNull { byName[it] }
        val seen = first.mapTo(HashSet()) { it.name }
        first + all.filter { it.name !in seen }
    } else {
        val hits = all.filter { it.name.contains(q) }
        hits.filter { it.name.startsWith(q) } + hits.filterNot { it.name.startsWith(q) }
    }
    return list.chunked(GRID_COLUMNS)
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Label(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.labelLarge)

@Composable
private fun Hint(res: Int) = Text(
    stringResource(res),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(16.dp),
)

@Composable
private fun SourceChip(kind: GlyphKind, label: Int, spec: IconSpec, onClick: () -> Unit) {
    FilterChip(selected = spec.kind == kind, onClick = onClick, label = { Text(stringResource(label)) })
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PercentSlider(label: Int, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text(stringResource(label, value), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun SymbolCell(name: String, selected: Boolean, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val path = remember(name) { SymbolLibrary.path(name)?.asComposePath() }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(11.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (path != null) {
                val k = size.minDimension / 24f
                withTransform({ scale(k, k, Offset.Zero) }) { drawPath(path, tint) }
            }
        }
    }
}

/** Preset swatches plus a hex field. */
@Composable
private fun ColorEditor(color: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(SWATCHES) { c ->
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            width = if (c == color) 3.dp else 1.dp,
                            color = if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable { onChange(c) },
                )
            }
        }
        var text by remember(color) { mutableStateOf("%06X".format(color and 0xFFFFFF)) }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val v = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6).uppercase()
                text = v
                if (v.length == 6) onChange(0xFF000000.toInt() or v.toInt(16))
            },
            singleLine = true,
            prefix = { Text("#") },
            label = { Text(stringResource(R.string.im_hex)) },
            modifier = Modifier.fillMaxWidth(0.5f),
        )
    }
}
