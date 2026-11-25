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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.iconmaker.BgKind
import com.invictus.kodex.core.iconmaker.GlyphKind
import com.invictus.kodex.core.iconmaker.TextFont
import com.invictus.kodex.core.iconmaker.SymbolLibrary
import com.invictus.kodex.ui.icons.XIcons

private const val GRID_COLUMNS = 7

private enum class EditorTab(val label: Int) {
    Icon(R.string.im_tab_icon),
    Colors(R.string.im_tab_colors),
    Size(R.string.im_tab_size),
    Name(R.string.im_tab_name),
}

private val APP_TABS = listOf(EditorTab.Icon, EditorTab.Colors, EditorTab.Size)
private val NOTIF_TABS = listOf(EditorTab.Icon, EditorTab.Size, EditorTab.Name)

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

/** Ready-made looks: icon color + background in one tap. */
private class QuickStyle(val fg: Int, val bg1: Int, val bg2: Int?)

private val QUICK_STYLES = listOf(
    QuickStyle(0xFFFFFFFF.toInt(), 0xFF7C4DFF.toInt(), 0xFF3D2BC4.toInt()),
    QuickStyle(0xFFFFFFFF.toInt(), 0xFF0EA5E9.toInt(), 0xFF2563EB.toInt()),
    QuickStyle(0xFFFFFFFF.toInt(), 0xFFF97316.toInt(), 0xFFEC4899.toInt()),
    QuickStyle(0xFFFFFFFF.toInt(), 0xFF34D399.toInt(), 0xFF059669.toInt()),
    QuickStyle(0xFFFFFFFF.toInt(), 0xFFF43F5E.toInt(), 0xFFBE123C.toInt()),
    QuickStyle(0xFFFFFFFF.toInt(), 0xFF1E293B.toInt(), 0xFF0F172A.toInt()),
    QuickStyle(0xFF111827.toInt(), 0xFFFFFFFF.toInt(), null),
    QuickStyle(0xFF111827.toInt(), 0xFFFACC15.toInt(), null),
    QuickStyle(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), null),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconMakerScreen(
    onBack: () -> Unit,
    viewModel: IconMakerViewModel = viewModel(factory = IconMakerViewModel.Factory),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val spec = state.spec
    val isApp = state.mode == MakerMode.App
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.effects.collect { snackbar.showSnackbar(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.setPicture(uri)
    }
    var preview by rememberSaveable { mutableStateOf(PreviewMode.Home) }
    var tab by rememberSaveable(state.mode) { mutableStateOf(EditorTab.Icon) }
    val tabs = if (isApp) APP_TABS else NOTIF_TABS

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_iconmaker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PillTabs(
                items = listOf(MakerMode.App to R.string.im_mode_app, MakerMode.Notification to R.string.im_mode_notif),
                selected = state.mode,
                onSelect = viewModel::setMode,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // The preview never scrolls away while you edit. Mode pills float over its bottom edge.
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                if (isApp) {
                    Box {
                        IconPreview(
                            mode = preview,
                            layers = state.layers,
                            shape = spec.legacyShape,
                            onShape = { s -> viewModel.edit { copy(legacyShape = s) } },
                        )
                        PillTabs(
                            items = PreviewMode.entries.map { it to it.label },
                            selected = preview,
                            onSelect = { preview = it },
                            compact = true,
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                        )
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(172.dp)) {
                        NotificationPreview(glyph = state.notifGlyph, accent = Color(state.notif.accent))
                    }
                }
            }

            // Editor sheet: tabs on top, only the body below them scrolls.
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.fillMaxSize()) {
                    EditorTabs(tabs, tab) { tab = it }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            EditorTab.Icon -> IconTab(state, viewModel) { picker.launch("image/*") }
                            EditorTab.Colors -> Panel {
                                SectionTitle(R.string.im_quick_styles)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(QUICK_STYLES) { q -> QuickStyleTile(q) { viewModel.applyStyle(q.fg, q.bg1, q.bg2) } }
                                }
                                SectionTitle(R.string.im_section_icon_color)
                                ColorEditor(spec.fgColor) { c -> viewModel.edit { copy(fgColor = c) } }
                                if (spec.kind == GlyphKind.Picture) {
                                    SwitchRow(R.string.im_tint_image, spec.tintPicture) { viewModel.edit { copy(tintPicture = it) } }
                                }
                                SectionTitle(R.string.im_section_bg)
                                PillTabs(
                                    items = listOf(BgKind.Solid to R.string.im_bg_solid, BgKind.Gradient to R.string.im_bg_gradient),
                                    selected = spec.bgKind,
                                    onSelect = { k -> viewModel.edit { copy(bgKind = k) } },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ColorEditor(spec.bg1) { c -> viewModel.edit { copy(bg1 = c) } }
                                if (spec.bgKind == BgKind.Gradient) {
                                    Label(R.string.im_bg_color2)
                                    ColorEditor(spec.bg2) { c -> viewModel.edit { copy(bg2 = c) } }
                                    ValueSlider(
                                        label = stringResource(R.string.im_bg_angle_label),
                                        value = "${spec.bgAngle}°",
                                        position = spec.bgAngle / 45f,
                                        range = 0f..7f,
                                        steps = 6,
                                    ) { v -> viewModel.edit { copy(bgAngle = (v.toInt() * 45) % 360) } }
                                }
                            }
                            EditorTab.Size -> Panel {
                                if (isApp) {
                                    IntSlider(R.string.im_size, spec.sizePct, 30..130) { viewModel.edit { copy(sizePct = it) } }
                                    IntSlider(R.string.im_offset_x, spec.offsetXPct, -30..30) { viewModel.edit { copy(offsetXPct = it) } }
                                    IntSlider(R.string.im_offset_y, spec.offsetYPct, -30..30) { viewModel.edit { copy(offsetYPct = it) } }
                                    SwitchRow(R.string.im_shadow, spec.shadow) { viewModel.edit { copy(shadow = it) } }
                                    TextButton(onClick = { viewModel.edit { copy(sizePct = 80, offsetXPct = 0, offsetYPct = 0) } }) {
                                        Text(stringResource(R.string.im_reset))
                                    }
                                } else {
                                    val n = state.notif
                                    IntSlider(R.string.im_size, n.sizePct, 40..100) { viewModel.editNotif { copy(sizePct = it) } }
                                    IntSlider(R.string.im_offset_x, n.offsetXPct, -20..20) { viewModel.editNotif { copy(offsetXPct = it) } }
                                    IntSlider(R.string.im_offset_y, n.offsetYPct, -20..20) { viewModel.editNotif { copy(offsetYPct = it) } }
                                    TextButton(onClick = { viewModel.editNotif { copy(sizePct = 86, offsetXPct = 0, offsetYPct = 0) } }) {
                                        Text(stringResource(R.string.im_reset))
                                    }
                                }
                            }
                            EditorTab.Name -> Panel {
                                OutlinedTextField(
                                    value = state.notif.name,
                                    onValueChange = { v -> viewModel.editNotif { copy(name = v.take(40)) } },
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.im_notif_name)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    stringResource(R.string.im_notif_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SectionTitle(R.string.im_notif_accent)
                                ColorEditor(state.notif.accent) { c -> viewModel.editNotif { copy(accent = c) } }
                            }
                        }
                    }
                    ActionBar(busy = state.busy, onSave = viewModel::saveZip, onApply = viewModel::requestApply)
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

@Composable
private fun ActionBar(busy: Boolean, onSave: () -> Unit, onApply: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onSave, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
            Text(stringResource(R.string.im_save_zip), maxLines = 1)
        }
        Button(onClick = onApply, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.im_apply), maxLines = 1)
        }
    }
}

/** A rounded segmented control: one pill highlights the current choice. */
@Composable
private fun <T> PillTabs(
    items: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    compact: Boolean = false,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp)
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((value, label) in items) {
            val sel = value == selected
            Box(
                Modifier
                    .then(if (scrollable) Modifier else Modifier.weight(1f))
                    .clip(CircleShape)
                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EditorTabs(tabs: List<EditorTab>, selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (t in tabs) {
            val sel = t == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(t) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(t.label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier.height(3.dp).width(28.dp).clip(CircleShape)
                        .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

/** A tab body that scrolls on its own (the preview and the tabs stay put). */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

private enum class Source { Text, Image, Icons }

@Composable
private fun IconTab(state: IconMakerViewModel.UiState, vm: IconMakerViewModel, pickImage: () -> Unit) {
    val spec = state.spec
    var source by rememberSaveable {
        mutableStateOf(
            when (spec.kind) {
                GlyphKind.Text -> Source.Text
                GlyphKind.Picture -> Source.Image
                GlyphKind.Symbol -> Source.Icons
            },
        )
    }
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PillTabs(
            items = listOf(Source.Text to R.string.im_src_text, Source.Image to R.string.im_src_image, Source.Icons to R.string.im_src_icons),
            selected = source,
            onSelect = { s ->
                source = s
                query = ""
                when (s) {
                    Source.Text -> vm.edit { copy(kind = GlyphKind.Text) }
                    Source.Icons -> vm.edit { copy(kind = GlyphKind.Symbol) }
                    Source.Image -> if (state.hasPicture) vm.edit { copy(kind = GlyphKind.Picture) } else pickImage()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (source) {
            Source.Text -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = spec.text,
                    onValueChange = { v -> vm.edit { copy(text = v.take(3), kind = GlyphKind.Text) } },
                    singleLine = true,
                    label = { Text(stringResource(R.string.im_text_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionTitle(R.string.im_text_style)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (f in TextFont.entries) {
                        FontTile(f, selected = spec.font == f) { vm.edit { copy(font = f, kind = GlyphKind.Text) } }
                    }
                }
                Text(
                    stringResource(R.string.im_text_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Source.Image -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = pickImage) {
                    Text(stringResource(if (state.hasPicture) R.string.im_change_image else R.string.im_pick_image))
                }
                Text(
                    stringResource(R.string.im_image_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Source.Icons -> IconGrid(state, vm, query) { query = it }
        }
    }
}

@Composable
private fun IconGrid(state: IconMakerViewModel.UiState, vm: IconMakerViewModel, query: String, onQuery: (String) -> Unit) {
    val symbols = state.symbols
    val rows = remember(symbols, query) { symbolRows(symbols, query) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.im_search_hint, symbols.size)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (state.symbolsLoading) Hint(R.string.im_loading_icons) else if (rows.isEmpty()) Hint(R.string.im_no_icons)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(rows, key = { it.first().name }) { row ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until GRID_COLUMNS) {
                        val s = row.getOrNull(i)
                        if (s != null) {
                            SymbolCell(
                                name = s.name,
                                selected = state.spec.kind == GlyphKind.Symbol && s.name == state.spec.symbol,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            ) { vm.edit { copy(kind = GlyphKind.Symbol, symbol = s.name) } }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
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

/** A letter style, shown as "Aa" in that style. */
@Composable
private fun FontTile(font: TextFont, selected: Boolean, onClick: () -> Unit) {
    val (family, weight, label) = when (font) {
        TextFont.Bold -> Triple(FontFamily.SansSerif, FontWeight.Bold, R.string.im_font_bold)
        TextFont.Light -> Triple(FontFamily.SansSerif, FontWeight.Light, R.string.im_font_light)
        TextFont.Serif -> Triple(FontFamily.Serif, FontWeight.Bold, R.string.im_font_serif)
        TextFont.Mono -> Triple(FontFamily.Monospace, FontWeight.Bold, R.string.im_font_mono)
        TextFont.Script -> Triple(FontFamily.Cursive, FontWeight.Normal, R.string.im_font_script)
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(72.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Aa", fontFamily = family, fontWeight = weight, fontSize = 24.sp)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(res: Int) =
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

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
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Label on the left, current value on the right, slider below. */
@Composable
private fun ValueSlider(label: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = position, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun IntSlider(label: Int, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    ValueSlider(
        label = stringResource(label),
        value = "$value%",
        position = value.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
    ) { onChange(it.toInt()) }
}

@Composable
private fun QuickStyleTile(q: QuickStyle, onClick: () -> Unit) {
    val brush = if (q.bg2 != null) Brush.linearGradient(listOf(Color(q.bg1), Color(q.bg2)), start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f))
    else Brush.linearGradient(listOf(Color(q.bg1), Color(q.bg1)))
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(q.fg)))
    }
}

@Composable
private fun SymbolCell(name: String, selected: Boolean, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val path = remember(name) { SymbolLibrary.path(name)?.asComposePath() }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(10.dp),
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
