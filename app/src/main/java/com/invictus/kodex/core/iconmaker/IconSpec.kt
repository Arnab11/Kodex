package com.invictus.kodex.core.iconmaker

import android.graphics.Bitmap

enum class GlyphKind { Symbol, Text, Picture }

enum class BgKind { None, Solid, Gradient }

/** Letter styles for the Text glyph. */
enum class TextFont { Bold, Light, Serif, Mono, Script }

/** Launcher masks used by the previews and by the legacy (pre-Android 8) PNGs. */
enum class IconShape { Circle, Squircle, Rounded, Teardrop, Square }

/** Everything that defines the icon. Sizes are percentages of the 66dp adaptive-icon safe zone. */
data class IconSpec(
    val kind: GlyphKind = GlyphKind.Text,
    val symbol: String = "bolt",
    val text: String = "K",
    val font: TextFont = TextFont.Bold,
    val picture: Bitmap? = null,
    val tintPicture: Boolean = false,
    /** Erase the image's plain background (flood-filled from its edges). */
    val cutout: Boolean = false,
    /** 0..100: how far a colour may differ from the background colour and still be erased. */
    val cutoutTolerance: Int = 30,
    val fgColor: Int = 0xFF1B1B1F.toInt(),
    val sizePct: Int = 80,
    val offsetXPct: Int = 0,
    val offsetYPct: Int = 0,
    val shadow: Boolean = false,
    val bgKind: BgKind = BgKind.Solid,
    val bg1: Int = 0xFFFFFFFF.toInt(),
    val bg2: Int = 0xFF3D2BC4.toInt(),
    /** Android gradient angle, multiples of 45: 0 = left to right, 90 = bottom to top. */
    val bgAngle: Int = 45,
    /** Mask for the legacy PNGs; also the mask the previews use. */
    val legacyShape: IconShape = IconShape.Circle,
)

/** Status-bar / notification small icon: the same glyph, white on transparent, exported per density. */
data class NotifSpec(
    val name: String = "ic_stat_notify",
    /** Glyph box as a percentage of the icon canvas. */
    val sizePct: Int = 86,
    val offsetXPct: Int = 0,
    val offsetYPct: Int = 0,
    /** Only used by the previews (what setColor() would tint the icon with). */
    val accent: Int = 0xFF7C4DFF.toInt(),
)
