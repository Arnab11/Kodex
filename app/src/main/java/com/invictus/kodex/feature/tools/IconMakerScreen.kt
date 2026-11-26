package com.invictus.kodex.feature.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.iconmaker.BgKind
import com.invictus.kodex.core.iconmaker.GlyphKind
import com.invictus.kodex.core.iconmaker.ImageFormat
import com.invictus.kodex.core.iconmaker.SymbolLibrary
import com.invictus.kodex.core.iconmaker.TextFont
import com.invictus.kodex.ui.icons.XIcons
import kotlin.math.roundToInt

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
    val isApp = state.mode == MakerMode.App
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.effects.collect { snackbar.showSnackbar(it) } }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.setPicture(uri)
    }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.setWallpaperUri(uri)
    }
    var preview by rememberSaveable { mutableStateOf(PreviewMode.Home) }
    // The editor opens on the Icon tab (Image source, with the "Choose image" button); the tools row collapses it.
    var openTab by rememberSaveable(state.mode) { mutableStateOf<EditorTab?>(EditorTab.Icon) }
    // Kept here so switching tools doesn't reset the Text / Image / Icons choice.
    var iconSource by rememberSaveable { mutableStateOf(Source.Image) }
    var shownTab by remember(state.mode) { mutableStateOf(EditorTab.Icon) }
    LaunchedEffect(openTab) { openTab?.let { shownTab = it } }
    val tabs = if (isApp) APP_TABS else NOTIF_TABS

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    PillTabs(
                        items = listOf(MakerMode.App to R.string.im_mode_app, MakerMode.Notification to R.string.im_mode_notif),
                        selected = state.mode,
                        onSelect = viewModel::setMode,
                        compact = true,
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val panelHeight = (maxHeight * 0.42f).coerceIn(230.dp, 360.dp)
            Column(Modifier.fillMaxSize()) {
                PreviewCard(
                    state = state,
                    preview = preview,
                    onPreview = { preview = it },
                    vm = viewModel,
                    pickWallpaper = { wallpaperPicker.launch("image/*") },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                EditorDock(
                    tabs = tabs,
                    openTab = openTab,
                    contentTab = openTab ?: shownTab,
                    panelHeight = panelHeight,
                    onToggle = { t -> openTab = if (openTab == t) null else t },
                    busy = state.busy,
                    onSave = { viewModel.openExport(ExportKind.Zip) },
                    onApply = { viewModel.openExport(ExportKind.Apply) },
                ) { tab -> TabPanel(tab, state, viewModel, isApp, iconSource, { iconSource = it }) { imagePicker.launch("image/*") } }
            }
        }
    }

    ExportDialog(state, viewModel)
}

// ---- Preview card -------------------------------------------------------------------------------------------

@Composable
private fun PreviewCard(
    state: IconMakerViewModel.UiState,
    preview: PreviewMode,
    onPreview: (PreviewMode) -> Unit,
    vm: IconMakerViewModel,
    pickWallpaper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (state.mode == MakerMode.App) {
            Box(Modifier.fillMaxSize()) {
                // a real-looking wallpaper behind all three previews
                WallpaperCanvas(state.wallpaperPreset, state.wallpaper, Modifier.fillMaxSize())
                IconPreview(
                    mode = preview,
                    layers = state.layers,
                    shape = state.spec.legacyShape,
                    onShape = { s -> vm.edit { copy(legacyShape = s) } },
                    light = wallpaperIsLight(state.wallpaperPreset, state.wallpaper),
                    modifier = Modifier.fillMaxSize(),
                )
                WallpaperButton(state, vm, pickWallpaper, Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 10.dp))
                PillTabs(
                    items = PreviewMode.entries.map { it to it.label },
                    selected = preview,
                    onSelect = onPreview,
                    compact = true,
                    glass = true,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NotificationPreview(glyph = state.notifGlyph, accent = Color(state.notif.accent))
            }
        }
    }
}

@Composable
private fun WallpaperButton(state: IconMakerViewModel.UiState, vm: IconMakerViewModel, pickWallpaper: () -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Color(0x66000000)).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(XIcons.Image, contentDescription = stringResource(R.string.im_wallpaper), tint = Color.White, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.im_wallpaper), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until WALLPAPER_COUNT) {
                        val selected = state.wallpaper == null && state.wallpaperPreset == i
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable {
                                    vm.setWallpaperPreset(i)
                                    open = false
                                },
                        ) { WallpaperCanvas(i, null, Modifier.fillMaxSize()) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        open = false
                        pickWallpaper()
                    }) { Text(stringResource(R.string.im_wp_gallery)) }
                    if (state.systemWallpaper != null) {
                        TextButton(onClick = {
                            vm.useSystemWallpaper()
                            open = false
                        }) { Text(stringResource(R.string.im_wp_system)) }
                    }
                }
            }
        }
    }
}

// ---- Editor dock (collapsed tools row + expandable panel + export) ----------------------------------------

@Composable
private fun EditorDock(
    tabs: List<EditorTab>,
    openTab: EditorTab?,
    contentTab: EditorTab,
    panelHeight: Dp,
    onToggle: (EditorTab) -> Unit,
    busy: Boolean,
    onSave: () -> Unit,
    onApply: () -> Unit,
    panel: @Composable (EditorTab) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            AnimatedVisibility(visible = openTab != null, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    Box(Modifier.fillMaxWidth().height(panelHeight)) { panel(contentTab) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (t in tabs) ToolButton(t, selected = t == openTab, modifier = Modifier.weight(1f)) { onToggle(t) }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
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
    }
}

/** One of the collapsed tools; tapping opens (or closes) its panel, and the chevron turns over. */
@Composable
private fun ToolButton(tab: EditorTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "tool-bg",
    )
    val turn by animateFloatAsState(if (selected) 180f else 0f, label = "tool-chevron")
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(tab.label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(10.dp)) {
            rotate(turn) {
                val p = Path().apply {
                    moveTo(size.width * 0.1f, size.height * 0.3f)
                    lineTo(size.width * 0.5f, size.height * 0.75f)
                    lineTo(size.width * 0.9f, size.height * 0.3f)
                }
                drawPath(p, fg, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ---- Panels -------------------------------------------------------------------------------------------------

@Composable
private fun TabPanel(
    tab: EditorTab,
    state: IconMakerViewModel.UiState,
    vm: IconMakerViewModel,
    isApp: Boolean,
    source: Source,
    onSource: (Source) -> Unit,
    pickImage: () -> Unit,
) {
    val spec = state.spec
    when (tab) {
        EditorTab.Icon -> IconTab(state, vm, source, onSource, pickImage)
        EditorTab.Colors -> Panel {
            SectionTitle(R.string.im_quick_styles)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(QUICK_STYLES) { q -> QuickStyleTile(q) { vm.applyStyle(q.fg, q.bg1, q.bg2) } }
            }
            SectionTitle(R.string.im_section_icon_color)
            ColorEditor(spec.fgColor) { c -> vm.edit { copy(fgColor = c) } }
            if (spec.kind == GlyphKind.Picture) {
                SwitchRow(R.string.im_tint_image, spec.tintPicture) { vm.edit { copy(tintPicture = it) } }
            }
            SectionTitle(R.string.im_section_bg)
            PillTabs(
                items = listOf(BgKind.None to R.string.im_bg_none, BgKind.Solid to R.string.im_bg_solid, BgKind.Gradient to R.string.im_bg_gradient),
                selected = spec.bgKind,
                onSelect = { k -> vm.edit { copy(bgKind = k) } },
                modifier = Modifier.fillMaxWidth(),
            )
            when (spec.bgKind) {
                BgKind.None -> Text(
                    stringResource(R.string.im_bg_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BgKind.Solid -> ColorEditor(spec.bg1) { c -> vm.edit { copy(bg1 = c) } }
                BgKind.Gradient -> {
                    ColorEditor(spec.bg1) { c -> vm.edit { copy(bg1 = c) } }
                    Label(R.string.im_bg_color2)
                    ColorEditor(spec.bg2) { c -> vm.edit { copy(bg2 = c) } }
                    ExpressiveSlider(
                        label = stringResource(R.string.im_bg_angle_label),
                        valueText = "${spec.bgAngle}°",
                        value = spec.bgAngle / 45f,
                        range = 0f..7f,
                        steps = 6,
                    ) { v -> vm.edit { copy(bgAngle = (v.roundToInt() * 45) % 360) } }
                }
            }
        }
        EditorTab.Size -> Panel {
            if (isApp) {
                IntSlider(R.string.im_size, spec.sizePct, 30..200) { vm.edit { copy(sizePct = it) } }
                IntSlider(R.string.im_offset_x, spec.offsetXPct, -30..30) { vm.edit { copy(offsetXPct = it) } }
                IntSlider(R.string.im_offset_y, spec.offsetYPct, -30..30) { vm.edit { copy(offsetYPct = it) } }
                SwitchRow(R.string.im_shadow, spec.shadow) { vm.edit { copy(shadow = it) } }
                TextButton(onClick = { vm.edit { copy(sizePct = 80, offsetXPct = 0, offsetYPct = 0) } }) {
                    Text(stringResource(R.string.im_reset))
                }
            } else {
                val n = state.notif
                IntSlider(R.string.im_size, n.sizePct, 40..100) { vm.editNotif { copy(sizePct = it) } }
                IntSlider(R.string.im_offset_x, n.offsetXPct, -20..20) { vm.editNotif { copy(offsetXPct = it) } }
                IntSlider(R.string.im_offset_y, n.offsetYPct, -20..20) { vm.editNotif { copy(offsetYPct = it) } }
                TextButton(onClick = { vm.editNotif { copy(sizePct = 86, offsetXPct = 0, offsetYPct = 0) } }) {
                    Text(stringResource(R.string.im_reset))
                }
            }
        }
        EditorTab.Name -> Panel {
            OutlinedTextField(
                value = state.notif.name,
                onValueChange = { v -> vm.editNotif { copy(name = v.take(40)) } },
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
            ColorEditor(state.notif.accent) { c -> vm.editNotif { copy(accent = c) } }
        }
    }
}

/** A panel body that scrolls on its own (the preview and the tools row stay put). */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

internal enum class Source { Text, Image, Icons }

@Composable
private fun IconTab(
    state: IconMakerViewModel.UiState,
    vm: IconMakerViewModel,
    source: Source,
    onSource: (Source) -> Unit,
    pickImage: () -> Unit,
) {
    val spec = state.spec
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PillTabs(
            items = listOf(Source.Text to R.string.im_src_text, Source.Image to R.string.im_src_image, Source.Icons to R.string.im_src_icons),
            selected = source,
            onSelect = { s ->
                onSource(s)
                query = ""
                when (s) {
                    Source.Text -> vm.edit { copy(kind = GlyphKind.Text) }
                    Source.Icons -> vm.edit { copy(kind = GlyphKind.Symbol) }
                    Source.Image -> if (state.hasPicture) vm.edit { copy(kind = GlyphKind.Picture) } else pickImage()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        when (source) {
            Source.Text -> Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
            Source.Image -> Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = pickImage) {
                    Text(stringResource(if (state.hasPicture) R.string.im_change_image else R.string.im_pick_image))
                }
                if (state.hasPicture) {
                    SwitchRow(R.string.im_cutout, spec.cutout) { vm.edit { copy(cutout = it) } }
                    if (spec.cutout) {
                        ExpressiveSlider(
                            label = stringResource(R.string.im_cutout_tolerance),
                            valueText = "${spec.cutoutTolerance}%",
                            value = spec.cutoutTolerance.toFloat(),
                            range = 0f..100f,
                        ) { v -> vm.edit { copy(cutoutTolerance = v.roundToInt()) } }
                    }
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

// ---- Export dialog ------------------------------------------------------------------------------------------

@Composable
private fun ExportDialog(state: IconMakerViewModel.UiState, vm: IconMakerViewModel) {
    val e = state.export ?: return
    AlertDialog(
        onDismissRequest = vm::dismissExport,
        title = { Text(stringResource(if (e.kind == ExportKind.Zip) R.string.im_save_zip else R.string.im_pick_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.im_export_format), style = MaterialTheme.typography.labelLarge)
                PillTabs(
                    items = listOf(ImageFormat.Png to R.string.im_fmt_png, ImageFormat.Webp to R.string.im_fmt_webp),
                    selected = state.format,
                    onSelect = vm::setFormat,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.im_fmt_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (e.kind == ExportKind.Apply) {
                    HorizontalDivider()
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        for (t in e.targets) {
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.applyTo(t) }.padding(horizontal = 8.dp, vertical = 10.dp),
                            ) {
                                Text(t.name, style = MaterialTheme.typography.bodyLarge)
                                Text(t.root.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (e.kind == ExportKind.Zip) TextButton(onClick = vm::saveZip) { Text(stringResource(R.string.im_save)) }
        },
        dismissButton = { TextButton(onClick = vm::dismissExport) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- Building blocks ----------------------------------------------------------------------------------------

/** A rounded segmented control: one pill highlights the current choice. [glass] is the translucent look for use over a wallpaper. */
@Composable
private fun <T> PillTabs(
    items: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    glass: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val container = if (glass) Color(0x66000000) else cs.surfaceContainerHigh
    val pill = if (glass) Color(0x40FFFFFF) else cs.primaryContainer
    Row(
        modifier.clip(CircleShape).background(container).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((value, label) in items) {
            val sel = value == selected
            val text = when {
                glass -> if (sel) Color.White else Color(0xCCFFFFFF)
                sel -> cs.onPrimaryContainer
                else -> cs.onSurfaceVariant
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (sel) pill else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = if (compact) 7.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = text,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Material 3 Expressive style slider: a tall pill whose filled part carries the label and the value, with a
 * handle at the end of the fill. Tap or drag anywhere on it.
 */
@Composable
private fun ExpressiveSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val span = range.endInclusive - range.start
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val currentOnChange by rememberUpdatedState(onChange)
    val cs = MaterialTheme.colorScheme

    fun update(x: Float) {
        if (widthPx <= 0) return
        var f = (x / widthPx).coerceIn(0f, 1f)
        if (steps > 0) {
            val n = steps + 1
            f = (f * n).roundToInt() / n.toFloat()
        }
        currentOnChange(range.start + f * span)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = cs.onSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f))
            Text(valueText, color = cs.primary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cs.surfaceContainerHighest)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(range.start, span, steps) { detectTapGestures { update(it.x) } }
                .pointerInput(range.start, span, steps) {
                    detectHorizontalDragGestures(onDragStart = { update(it.x) }) { change, _ ->
                        change.consume()
                        update(change.position.x)
                    }
                },
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(cs.primary))
            val handleEdge = with(density) { 10.dp.roundToPx() }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(((widthPx * fraction).roundToInt() - handleEdge).coerceAtLeast(with(density) { 4.dp.roundToPx() }), 0) }
                    .size(width = 4.dp, height = 22.dp)
                    .clip(CircleShape)
                    .background(cs.onPrimary),
            )
        }
    }
}

@Composable
private fun IntSlider(label: Int, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    ExpressiveSlider(
        label = stringResource(label),
        valueText = "$value%",
        value = value.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
    ) { onChange(it.roundToInt()) }
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
