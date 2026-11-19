package com.invictus.kodex.core.iconmaker

import androidx.core.graphics.ColorUtils

/** How a brand glyph is dressed as a launcher icon. [bg2] != null means a gradient at [angle]. */
data class BrandLook(val bg1: Int, val bg2: Int?, val fg: Int, val sizePct: Int, val angle: Int = 45)

/**
 * Original = how the app's real launcher icon looks (white tile with a coloured logo, brand-coloured tile with
 * a white logo, black tile, ...). The other styles dress any brand glyph that way.
 */
object BrandLooks {
    enum class Style { Original, Color, White, Black }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF0B0B0F.toInt()

    private val ON_WHITE = setOf(
        "youtube", "youtubemusic", "youtubekids", "google", "googlechrome", "googledrive", "googlemaps",
        "googlephotos", "googleplay", "gmail", "googlemeet", "googlecalendar", "googlenews", "googlepay",
        "firefoxbrowser", "brave", "opera", "vlcmediaplayer", "safari", "duckduckgo", "stackoverflow", "medium",
    )
    private val ON_BLACK = setOf("spotify", "netflix")
    private val SIZES = mapOf(
        "youtube" to 86, "youtubekids" to 80, "netflix" to 54, "spotify" to 84, "googlechrome" to 84,
        "googleplay" to 74, "gmail" to 80, "vlcmediaplayer" to 78, "x" to 64, "github" to 74,
    )

    fun look(symbol: SymbolLibrary.Symbol, style: Style): BrandLook {
        val slug = symbol.name.removePrefix("@")
        val brand = 0xFF000000.toInt() or symbol.brandColor
        val size = SIZES[slug] ?: 70
        val resolved = if (style != Style.Original) style else when {
            slug == "instagram" -> return BrandLook(0xFFFCAF45.toInt(), 0xFF833AB4.toInt(), WHITE, 66)
            slug in ON_WHITE -> Style.White
            slug in ON_BLACK -> Style.Black
            else -> Style.Color
        }
        val lum = ColorUtils.calculateLuminance(brand)
        return when (resolved) {
            Style.White -> BrandLook(WHITE, null, if (lum > 0.85) BLACK else brand, size)
            Style.Black -> BrandLook(BLACK, null, if (lum < 0.12) WHITE else brand, size)
            else -> BrandLook(brand, null, if (lum > 0.7) BLACK else WHITE, size)
        }
    }
}
