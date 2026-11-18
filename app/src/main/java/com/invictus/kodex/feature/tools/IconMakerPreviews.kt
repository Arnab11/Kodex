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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.invictus.kodex.R
import com.invictus.kodex.core.iconmaker.IconShape
import com.invictus.kodex.core.iconmaker.IconShapes
import com.invictus.kodex.ui.icons.XIcons
import kotlin.math.roundToInt

private typealias Layers = IconMakerViewModel.Layers

/** Compose Shape for one of the launcher masks (used to clip the dummy apps in the home-screen mock). */
private class MaskShape(private val shape: IconShape) : Shape {
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

enum class PreviewMode(val label: Int) {
    Launcher(R.string.im_preview_launcher),
    Shapes(R.string.im_preview_shapes),
    Home(R.string.im_preview_home),
    Themed(R.string.im_preview_themed),
    LayerStack(R.string.im_preview_layers),
}

@Composable
fun previewModeLabel(mode: PreviewMode) = stringResource(mode.label)

/** One preview, [PREVIEW_HEIGHT] tall, switched by the mode chips above it. */
val PREVIEW_HEIGHT = 224.dp

@Composable
fun IconPreview(mode: PreviewMode, layers: Layers?, shape: IconShape, onShape: (IconShape) -> Unit) {
    Box(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT), contentAlignment = Alignment.Center) {
        when (mode) {
            PreviewMode.Launcher -> LauncherPreview(layers, shape)
            PreviewMode.Shapes -> ShapesPreview(layers, shape, onShape)
            PreviewMode.Home -> HomePreview(layers, shape)
            PreviewMode.Themed -> ThemedPreview(layers, shape)
            PreviewMode.LayerStack -> LayersPreview(layers)
        }
    }
}

@Composable
private fun LauncherPreview(layers: Layers?, shape: IconShape) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var safe by remember { mutableStateOf(false) }
    val parallax by animateOffsetAsState(drag, label = "parallax")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LayeredIcon(
            layers = layers,
            shape = shape,
            parallax = parallax,
            safeZone = safe,
            modifier = Modifier
                .size(148.dp)
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = safe, onClick = { safe = !safe }, label = { Text(stringResource(R.string.im_safe_zone)) })
            Text(
                stringResource(R.string.im_drag_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShapesPreview(layers: Layers?, selected: IconShape, onShape: (IconShape) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (shape in IconShape.entries) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .then(
                            if (shape == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            else Modifier,
                        )
                        .clickable { onShape(shape) }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LayeredIcon(layers, shape, Modifier.size(54.dp))
                    Text(stringResource(shapeLabel(shape)), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            for (d in listOf(24, 32, 48, 72)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LayeredIcon(layers, selected, Modifier.size(d.dp))
                    Text("${d}dp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private class Dummy(val label: String, val icon: ImageVector, val color: Color)

private val DUMMIES = listOf(
    Dummy("Files", XIcons.Folder, Color(0xFFF5A524)),
    Dummy("Search", XIcons.Search, Color(0xFF3B82F6)),
    Dummy("Paint", XIcons.Palette, Color(0xFFEC4899)),
    Dummy("Gallery", XIcons.Image, Color(0xFF10B981)),
    Dummy("Code", XIcons.Code, Color(0xFF6366F1)),
    Dummy("Shell", XIcons.Terminal, Color(0xFF0EA5E9)),
    Dummy("Tools", XIcons.Tools, Color(0xFFF97316)),
    Dummy("Settings", XIcons.Settings, Color(0xFF64748B)),
)

@Composable
private fun HomePreview(layers: Layers?, shape: IconShape) {
    val maskShape = remember(shape) { MaskShape(shape) }
    @Composable
    fun app(label: String, content: @Composable () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(52.dp).clip(maskShape), contentAlignment = Alignment.Center) { content() }
            Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
    @Composable
    fun dummy(d: Dummy) = app(d.label) {
        Box(Modifier.fillMaxSize().background(d.color), contentAlignment = Alignment.Center) {
            Icon(d.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B2A49), Color(0xFF4B2E83), Color(0xFF0F766E)))),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                dummy(DUMMIES[0])
                app(stringResource(R.string.im_app_label)) { LayeredIcon(layers, IconShape.Square, Modifier.fillMaxSize()) }
                dummy(DUMMIES[1])
                dummy(DUMMIES[2])
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                dummy(DUMMIES[3]); dummy(DUMMIES[4]); dummy(DUMMIES[5]); dummy(DUMMIES[6])
            }
        }
    }
}

@Composable
private fun ThemedPreview(layers: Layers?, shape: IconShape) {
    val cs = MaterialTheme.colorScheme
    val tiles = listOf(
        Triple(R.string.im_light, Color(0xFFD6E3FF) to Color(0xFF1B3B6F), null),
        Triple(R.string.im_dark, Color(0xFF284777) to Color(0xFFD6E3FF), null),
        Triple(R.string.im_your_theme, cs.primaryContainer to cs.onPrimaryContainer, null),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((label, colors, _) in tiles) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemedIcon(layers, shape, colors.first, colors.second, Modifier.size(86.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun LayersPreview(layers: Layers?) {
    val checker = MaterialTheme.colorScheme.surfaceContainerHighest
    val safeLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    @Composable
    fun tile(label: Int, content: @Composable () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(92.dp).clip(RoundedCornerShape(12.dp)).background(checker)) { content() }
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        tile(R.string.im_layer_bg) { LayerImage(layers?.bg, safeLine, showSafe = false) }
        tile(R.string.im_layer_fg) { LayerImage(layers?.fg, safeLine, showSafe = true) }
        tile(R.string.im_layer_mono) { LayerImage(layers?.mono, safeLine, showSafe = true, tint = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun LayerImage(image: androidx.compose.ui.graphics.ImageBitmap?, safeLine: Color, showSafe: Boolean, tint: Color? = null) {
    Canvas(Modifier.fillMaxSize()) {
        if (image != null) {
            drawImage(
                image,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.SrcIn) },
            )
        }
        if (showSafe) {
            drawCircle(
                color = safeLine,
                radius = size.minDimension * 33f / 108f,
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
        }
    }
}
