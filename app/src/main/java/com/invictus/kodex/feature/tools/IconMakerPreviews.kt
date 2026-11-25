package com.invictus.kodex.feature.tools

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import com.invictus.kodex.R
import com.invictus.kodex.core.iconmaker.IconShape
import com.invictus.kodex.core.iconmaker.IconShapes
import com.invictus.kodex.ui.icons.XIcons
import kotlin.math.roundToInt

private typealias Layers = IconMakerViewModel.Layers

/** Compose Shape for one of the launcher masks. */
class MaskShape(private val shape: IconShape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(IconShapes.path(shape, size.minDimension).asComposePath())
}

/**
 * The adaptive icon as a launcher draws it: both 108dp layers behind a mask that shows the central 72dp.
 * [parallax] (-1..1) moves the layers against each other like a launcher does on touch / tilt.
 */
@Composable
fun LayeredIcon(
    layers: Layers?,
    shape: IconShape,
    modifier: Modifier = Modifier,
    parallax: Offset = Offset.Zero,
    safeZone: Boolean = false,
) {
    Canvas(modifier) {
        val l = layers ?: return@Canvas
        val s = size.minDimension
        val full = (s * 1.5f).roundToInt()
        val base = -(full - s) / 2f
        clipPath(IconShapes.path(shape, s).asComposePath()) {
            drawImage(
                l.bg,
                dstOffset = IntOffset((base - parallax.x * s * 0.02f).roundToInt(), (base - parallax.y * s * 0.02f).roundToInt()),
                dstSize = IntSize(full, full),
            )
            drawImage(
                l.fg,
                dstOffset = IntOffset((base + parallax.x * s * 0.05f).roundToInt(), (base + parallax.y * s * 0.05f).roundToInt()),
                dstSize = IntSize(full, full),
            )
        }
        if (safeZone) {
            drawCircle(
                color = Color.White.copy(alpha = 0.95f),
                radius = s * 33f / 72f,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
    }
}

/** Android 13 themed icon: the monochrome layer tinted with [fg] on a flat [bg]. */
@Composable
private fun ThemedIcon(layers: Layers?, shape: IconShape, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val l = layers ?: return@Canvas
        val s = size.minDimension
        val full = (s * 1.5f).roundToInt()
        val base = (-(full - s) / 2f).roundToInt()
        clipPath(IconShapes.path(shape, s).asComposePath()) {
            drawRect(bg)
            drawImage(
                l.mono,
                dstOffset = IntOffset(base, base),
                dstSize = IntSize(full, full),
                colorFilter = ColorFilter.tint(fg, BlendMode.SrcIn),
            )
        }
    }
}

/** Three previews: a real-feeling home screen, the icon itself (touch + masks), and the Android 13 themed icon. */
enum class PreviewMode(val label: Int) {
    Home(R.string.im_preview_home),
    Icon(R.string.im_preview_icon),
    Themed(R.string.im_preview_themed),
}

@Composable
fun previewModeLabel(mode: PreviewMode) = stringResource(mode.label)

/** Height of the preview card's picture; the mode pills float over its bottom 46dp. */
val PREVIEW_HEIGHT = 196.dp
private val PILL_SPACE = 46.dp

@Composable
fun IconPreview(mode: PreviewMode, layers: Layers?, shape: IconShape, onShape: (IconShape) -> Unit) {
    Box(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT), contentAlignment = Alignment.Center) {
        when (mode) {
            PreviewMode.Home -> HomePreview(layers, shape)
            PreviewMode.Icon -> IconModePreview(layers, shape, onShape)
            PreviewMode.Themed -> ThemedPreview(layers, shape)
        }
    }
}

// ---- Home screen -------------------------------------------------------------------------------------------

private sealed interface PreviewApp {
    val label: String
}

/** A one-colour logo on a tile (solid or gradient). [size] is the logo's share of the tile. */
private class GlyphApp(
    override val label: String,
    val glyph: String,
    val bg1: Color,
    val bg2: Color?,
    val fg: Color,
    val size: Float,
) : PreviewApp

/** Play Store: the four-colour triangle on white, drawn directly. */
private class PlayApp(override val label: String) : PreviewApp

private val NEIGHBOURS: List<PreviewApp> = listOf(
    GlyphApp("YouTube", PreviewGlyphs.YOUTUBE, Color.White, null, Color(0xFFFF0000), 0.64f),
    PlayApp("Play Store"),
    GlyphApp("Gmail", PreviewGlyphs.GMAIL, Color.White, null, Color(0xFFEA4335), 0.58f),
    GlyphApp("WhatsApp", PreviewGlyphs.WHATSAPP, Color(0xFF25D366), null, Color.White, 0.58f),
    GlyphApp("Instagram", PreviewGlyphs.INSTAGRAM, Color(0xFFFCAF45), Color(0xFF833AB4), Color.White, 0.54f),
    GlyphApp("Spotify", PreviewGlyphs.SPOTIFY, Color(0xFF0B0B0F), null, Color(0xFF1ED760), 0.64f),
    GlyphApp("Telegram", PreviewGlyphs.TELEGRAM, Color(0xFF26A5E4), null, Color.White, 0.58f),
)

@Composable
private fun GlyphTile(app: GlyphApp, modifier: Modifier = Modifier) {
    val path = remember(app.glyph) { runCatching { PathParser.createPathFromPathData(app.glyph)?.asComposePath() }.getOrNull() }
    Canvas(modifier) {
        if (app.bg2 != null) {
            drawRect(Brush.linearGradient(listOf(app.bg1, app.bg2), start = Offset(0f, size.height), end = Offset(size.width, 0f)))
        } else {
            drawRect(app.bg1)
        }
        if (path != null) {
            val box = size.minDimension * app.size
            withTransform({
                translate((size.width - box) / 2f, (size.height - box) / 2f)
                scale(box / 24f, box / 24f, Offset.Zero)
            }) { drawPath(path, app.fg) }
        }
    }
}

@Composable
private fun PlayTile(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color.White)
        val w = size.width
        val h = size.height
        fun tri(a: Offset, b: Offset, c: Offset, color: Color) {
            val p = Path().apply {
                moveTo(a.x * w, a.y * h)
                lineTo(b.x * w, b.y * h)
                lineTo(c.x * w, c.y * h)
                close()
            }
            drawPath(p, color)
        }
        val a = Offset(0.28f, 0.17f)
        val b = Offset(0.28f, 0.83f)
        val c = Offset(0.80f, 0.50f)
        val p = Offset(0.50f, 0.50f)
        val e = Offset(0.52f, 0.68f)
        tri(a, b, p, Color(0xFF00A0FF))
        tri(a, p, c, Color(0xFF00D26A))
        tri(p, e, c, Color(0xFFFFD500))
        tri(b, p, e, Color(0xFFFF3A44))
    }
}

@Composable
private fun AppTile(app: PreviewApp, modifier: Modifier = Modifier) {
    when (app) {
        is GlyphApp -> GlyphTile(app, modifier)
        is PlayApp -> PlayTile(modifier)
    }
}

/** Your icon among real-looking apps on a wallpaper, with a status bar: the way it will actually be judged. */
@Composable
private fun HomePreview(layers: Layers?, shape: IconShape) {
    val mask = remember(shape) { MaskShape(shape) }
    @Composable
    fun slot(label: String, content: @Composable () -> Unit) {
        Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(44.dp).clip(mask)) { content() }
            Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
    val cells: List<@Composable () -> Unit> = buildList {
        NEIGHBOURS.forEachIndexed { i, app ->
            if (i == 1) add { slot(stringResource(R.string.im_app_label)) { LayeredIcon(layers, shape, Modifier.fillMaxSize()) } }
            add { slot(app.label) { AppTile(app, Modifier.fillMaxSize()) } }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF0F2A4A), Color(0xFF3B2A78), Color(0xFF0E6F6B)))),
    ) {
        // a soft vignette so the floating pills read well over the wallpaper
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000))),
            ),
        )
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = PILL_SPACE),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("9:41", color = Color.White, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
                    Box(Modifier.width(16.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.9f)))
                }
            }
            for (row in cells.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (cell in row) cell()
                }
            }
        }
    }
}

// ---- Icon ----------------------------------------------------------------------------------------------------

@Composable
private fun IconModePreview(layers: Layers?, selected: IconShape, onShape: (IconShape) -> Unit) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var safe by remember { mutableStateOf(false) }
    val parallax by animateOffsetAsState(drag, label = "parallax")
    Column(
        Modifier.fillMaxSize().padding(bottom = PILL_SPACE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LayeredIcon(
                layers = layers,
                shape = selected,
                parallax = parallax,
                safeZone = safe,
                modifier = Modifier
                    .size(88.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { drag = Offset.Zero },
                            onDragCancel = { drag = Offset.Zero },
                        ) { change, amount ->
                            change.consume()
                            drag = Offset((drag.x + amount.x / 40f).coerceIn(-1f, 1f), (drag.y + amount.y / 40f).coerceIn(-1f, 1f))
                        }
                    },
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = safe, onClick = { safe = !safe }, label = { Text(stringResource(R.string.im_safe_zone)) })
                Text(
                    stringResource(R.string.im_drag_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(120.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (shape in IconShape.entries) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            if (shape == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                            else Modifier,
                        )
                        .clickable { onShape(shape) }
                        .padding(4.dp),
                ) {
                    LayeredIcon(layers, shape, Modifier.size(34.dp))
                }
            }
        }
    }
}

fun shapeLabel(shape: IconShape): Int = when (shape) {
    IconShape.Circle -> R.string.im_shape_circle
    IconShape.Squircle -> R.string.im_shape_squircle
    IconShape.Rounded -> R.string.im_shape_rounded
    IconShape.Teardrop -> R.string.im_shape_teardrop
    IconShape.Square -> R.string.im_shape_square
}

// ---- Themed --------------------------------------------------------------------------------------------------

@Composable
private fun ThemedPreview(layers: Layers?, shape: IconShape) {
    val cs = MaterialTheme.colorScheme
    val tiles = listOf(
        Triple(R.string.im_light, Color(0xFFD6E3FF), Color(0xFF1B3B6F)),
        Triple(R.string.im_dark, Color(0xFF284777), Color(0xFFD6E3FF)),
        Triple(R.string.im_your_theme, cs.primaryContainer, cs.onPrimaryContainer),
    )
    Row(
        Modifier.fillMaxSize().padding(bottom = PILL_SPACE),
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, bg, fg) in tiles) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemedIcon(layers, shape, bg, fg, Modifier.size(68.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The 96px white glyph, tinted, at [size]. */
@Composable
private fun StatGlyph(glyph: ImageBitmap?, tint: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        if (glyph != null) {
            drawImage(
                glyph,
                dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            )
        }
    }
}

/** Status bar (dark + light) and a notification card, as the small icon really appears. */
@Composable
fun NotificationPreview(glyph: ImageBitmap?, accent: Color) {
    val cs = MaterialTheme.colorScheme
    @Composable
    fun bar(bg: Color, fg: Color, modifier: Modifier) {
        Row(
            modifier.height(28.dp).clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("12:30", color = fg, fontSize = 11.sp)
            StatGlyph(glyph, fg, 16.dp)
            Box(Modifier.size(10.dp).clip(CircleShape).background(fg.copy(alpha = 0.45f)))
            Box(Modifier.size(10.dp).clip(CircleShape).background(fg.copy(alpha = 0.45f)))
            Box(Modifier.weight(1f))
            Box(Modifier.width(16.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.8f)))
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bar(Color(0xFF111418), Color.White, Modifier.weight(1f))
            bar(Color(0xFFF2F4F8), Color(0xFF1B1B1F), Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                StatGlyph(glyph, Color.White, 18.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.im_notif_title), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    stringResource(R.string.im_notif_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            for (d in listOf(24, 36, 48)) {
                Box(Modifier.size((d + 12).dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                    StatGlyph(glyph, Color.White, d.dp)
                }
            }
            Text(
                stringResource(R.string.im_notif_sizes),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
