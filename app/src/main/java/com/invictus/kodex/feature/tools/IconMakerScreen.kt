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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.iconmaker.BgKind
import com.invictus.kodex.core.iconmaker.BrandLooks
import com.invictus.kodex.core.iconmaker.GlyphKind
import com.invictus.kodex.core.iconmaker.IconShape
import com.invictus.kodex.core.iconmaker.SymbolLibrary
import com.invictus.kodex.ui.icons.XIcons

private const val GRID_COLUMNS = 7
private const val BRAND_COLUMNS = 4

private enum class EditorTab(val label: Int) {
    Icon(R.string.im_tab_icon),
    Color(R.string.im_tab_color),
    Background(R.string.im_tab_background),
    Layout(R.string.im_tab_layout),
    Details(R.string.im_tab_details),
}

private val APP_TABS = listOf(EditorTab.Icon, EditorTab.Color, EditorTab.Background, EditorTab.Layout)
private val NOTIF_TABS = listOf(EditorTab.Icon, EditorTab.Layout, EditorTab.Details)

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
    val isApp = state.mode == MakerMode.App
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.effects.collect { snackbar.showSnackbar(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.setPicture(uri)
    }
    var preview by rememberSaveable { mutableStateOf(PreviewMode.Home) }
    var tabIndex by rememberSaveable(state.mode) { mutableStateOf(0) }
    val tabs = if (isApp) APP_TABS else NOTIF_TABS
    val tab = tabs[tabIndex.coerceIn(0, tabs.lastIndex)]

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = isApp,
                            onClick = { viewModel.setMode(MakerMode.App) },
                            label = { Text(stringResource(R.string.im_mode_app)) },
                        )
                        FilterChip(
                            selected = !isApp,
                            onClick = { viewModel.setMode(MakerMode.Notification) },
                            label = { Text(stringResource(R.string.im_mode_notif)) },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
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
            // Pinned: the preview stays visible while you edit below it; only the tab body scrolls.
            if (isApp) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (m in PreviewMode.entries) {
                        FilterChip(selected = preview == m, onClick = { preview = m }, label = { Text(previewModeLabel(m)) })
                    }
                }
                IconPreview(
                    mode = preview,
                    layers = state.layers,
                    shape = spec.legacyShape,
                    onShape = { s -> viewModel.edit { copy(legacyShape = s) } },
                    brandsReady = state.brands.isNotEmpty(),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT + 40.dp)) {
                    NotificationPreview(glyph = state.notifGlyph, accent = Color(state.notif.accent))
                }
            }
            HorizontalDivider()
            EditorTabs(tabs, tab) { tabIndex = tabs.indexOf(it) }
            HorizontalDivider()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    EditorTab.Icon -> IconTab(state, viewModel) { picker.launch("image/*") }
                    EditorTab.Color -> Panel {
                        Label(R.string.im_section_fg)
                        ColorEditor(spec.fgColor) { c -> viewModel.edit { copy(fgColor = c) } }
                        if (spec.kind == GlyphKind.Picture) {
                            SwitchRow(R.string.im_tint_image, spec.tintPicture) { viewModel.edit { copy(tintPicture = it) } }
                        }
                    }
                    EditorTab.Background -> Panel {
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
                    EditorTab.Layout -> Panel {
                        if (isApp) {
                            PercentSlider(R.string.im_size, spec.sizePct, 30..130) { viewModel.edit { copy(sizePct = it) } }
                            PercentSlider(R.string.im_offset_x, spec.offsetXPct, -30..30) { viewModel.edit { copy(offsetXPct = it) } }
                            PercentSlider(R.string.im_offset_y, spec.offsetYPct, -30..30) { viewModel.edit { copy(offsetYPct = it) } }
                            SwitchRow(R.string.im_shadow, spec.shadow) { viewModel.edit { copy(shadow = it) } }
                        } else {
                            val n = state.notif
                            PercentSlider(R.string.im_size, n.sizePct, 40..100) { viewModel.editNotif { copy(sizePct = it) } }
                            PercentSlider(R.string.im_offset_x, n.offsetXPct, -20..20) { viewModel.editNotif { copy(offsetXPct = it) } }
                            PercentSlider(R.string.im_offset_y, n.offsetYPct, -20..20) { viewModel.editNotif { copy(offsetYPct = it) } }
                        }
                    }
                    EditorTab.Details -> Panel {
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
                        Label(R.string.im_notif_accent)
                        ColorEditor(state.notif.accent) { c -> viewModel.editNotif { copy(accent = c) } }
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

@Composable
private fun EditorTabs(tabs: List<EditorTab>, selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        for (t in tabs) {
            val sel = t == selected
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { onSelect(t) }.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(t.label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.height(3.dp).width(26.dp).clip(CircleShape)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

private enum class Source { Icons, Brands, Text, Image }

@Composable
private fun IconTab(state: IconMakerViewModel.UiState, vm: IconMakerViewModel, pickImage: () -> Unit) {
    val spec = state.spec
    var source by rememberSaveable { mutableStateOf(Source.Icons) }
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val items = listOf(
                Source.Icons to R.string.im_src_icons,
                Source.Brands to R.string.im_src_brands,
                Source.Text to R.string.im_src_text,
                Source.Image to R.string.im_src_image,
            )
            for ((s, label) in items) {
                FilterChip(
                    selected = source == s,
                    onClick = {
                        source = s
                        query = ""
                        when (s) {
                            Source.Icons, Source.Brands -> vm.edit { copy(kind = GlyphKind.Symbol) }
                            Source.Text -> vm.edit { copy(kind = GlyphKind.Text) }
                            Source.Image -> if (state.hasPicture) vm.edit { copy(kind = GlyphKind.Picture) } else pickImage()
                        }
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        when (source) {
            Source.Icons -> IconGrid(state, vm, query) { query = it }
            Source.Brands -> BrandGrid(state, vm, query) { query = it }
            Source.Text -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = spec.text,
                    onValueChange = { v -> vm.edit { copy(text = v.take(3), kind = GlyphKind.Text) } },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.im_text_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Source.Image -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = pickImage) {
                    Text(stringResource(if (state.hasPicture) R.string.im_change_image else R.string.im_pick_image))
                }
                Text(
                    stringResource(R.string.im_image_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, hint: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(hint) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun IconGrid(state: IconMakerViewModel.UiState, vm: IconMakerViewModel, query: String, onQuery: (String) -> Unit) {
    val symbols = state.symbols
    val rows = remember(symbols, query) { symbolRows(symbols, query) }
    Column(Modifier.fillMaxSize()) {
        SearchField(query, stringResource(R.string.im_search_hint, symbols.size), onQuery)
        if (state.symbolsLoading) Hint(R.string.im_loading_icons) else if (rows.isEmpty()) Hint(R.string.im_no_icons)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(rows, key = { it.first().name }) { row ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until GRID_COLUMNS) {
                        val s = row.getOrNull(i)
                        if (s != null) {
                            SymbolCell(
                                name = s.name,
                                selected = s.name == state.spec.symbol,
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

@Composable
private fun BrandGrid(state: IconMakerViewModel.UiState, vm: IconMakerViewModel, query: String, onQuery: (String) -> Unit) {
    val brands = state.brands
    val rows = remember(brands, query) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) brands else brands.filter { it.searchKey.contains(q) }).chunked(BRAND_COLUMNS)
    }
    val selectedBrand = state.spec.symbol.startsWith("@") && state.spec.kind == GlyphKind.Symbol
    Column(Modifier.fillMaxSize()) {
        SearchField(query, stringResource(R.string.im_search_brands, brands.size), onQuery)
        if (selectedBrand) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val styles = listOf(
                    BrandLooks.Style.Original to R.string.im_brand_original,
                    BrandLooks.Style.Color to R.string.im_brand_color,
                    BrandLooks.Style.White to R.string.im_brand_white,
                    BrandLooks.Style.Black to R.string.im_brand_black,
                )
                for ((style, label) in styles) {
                    FilterChip(selected = false, onClick = { vm.brandStyle(style) }, label = { Text(stringResource(label)) })
                }
            }
        }
        if (rows.isEmpty()) Hint(R.string.im_no_icons)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(rows, key = { it.first().name }) { row ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until BRAND_COLUMNS) {
                        val b = row.getOrNull(i)
                        if (b != null) {
                            BrandCell(b, selected = b.name == state.spec.symbol && state.spec.kind == GlyphKind.Symbol, modifier = Modifier.weight(1f)) {
                                vm.useBrand(b)
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandCell(symbol: SymbolLibrary.Symbol, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val look = remember(symbol.name) { BrandLooks.look(symbol, BrandLooks.Style.Original) }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BrandTile(symbol, look, IconShape.Rounded, Modifier.size(52.dp))
        Text(symbol.title, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
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
