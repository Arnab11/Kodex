package com.invictus.kodex.core.iconmaker

import android.graphics.Bitmap
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the icon as a complete Android resource set under a module's src/main: legacy mipmaps (square + round),
 * adaptive icon XML with foreground / background / monochrome, and the 512px Play Store icon.
 * New resources use kodex-specific names so they never clash with a project's own ic_launcher_foreground.
 * Blocking: call off the main thread.
 */
/** Image format of the exported bitmaps (the Play Store icon is always PNG). */
enum class ImageFormat(val ext: String) { Png("png"), Webp("webp") }

/** Which parts of the launcher icon to write. */
data class IconOptions(
    val adaptive: Boolean = true,
    val themed: Boolean = true,
    val legacy: Boolean = true,
    val round: Boolean = true,
    val playStore: Boolean = true,
) {
    val any get() = adaptive || legacy || round || playStore

    companion object { val ALL = IconOptions() }
}

object IconExporter {
    private val DENSITIES = listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
    private const val FG = "ic_launcher_kodex_fg"
    private const val BG = "ic_launcher_kodex_bg"
    private const val MONO = "ic_launcher_kodex_mono"

    private fun compress(bmp: Bitmap, f: File, format: ImageFormat) {
        f.parentFile?.mkdirs()
        val cf = if (format == ImageFormat.Webp) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
        f.outputStream().use { bmp.compress(cf, 100, it) }
    }

    private fun iconNames(main: File): Pair<String, String> {
        val manifest = File(main, "AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty()
        val name = Regex("android:icon=\"@mipmap/([A-Za-z0-9_]+)\"").find(manifest)?.groupValues?.get(1) ?: "ic_launcher"
        val roundName = Regex("android:roundIcon=\"@mipmap/([A-Za-z0-9_]+)\"").find(manifest)?.groupValues?.get(1) ?: "${name}_round"
        return name to roundName
    }

    /**
     * What the project at [main] already has, so an apply keeps its current behaviour: adaptive XML (and whether
     * it carries a monochrome layer), legacy and round mipmaps, the Play Store PNG. A bare project gets everything.
     */
    fun detect(main: File): IconOptions {
        val res = File(main, "res")
        val (name, roundName) = iconNames(main)
        val manifest = File(main, "AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty()
        val anydpi = res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap-anydpi") }.orEmpty()
        val adaptiveXml = anydpi.map { File(it, "$name.xml") }.firstOrNull { it.isFile && it.readText().contains("<adaptive-icon") }
        val dpiDirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap-") && !f.name.startsWith("mipmap-anydpi") }.orEmpty()
        fun has(n: String) = dpiDirs.any { File(it, "$n.png").isFile || File(it, "$n.webp").isFile }
        val found = IconOptions(
            adaptive = adaptiveXml != null,
            themed = adaptiveXml?.readText()?.contains("<monochrome") == true,
            legacy = has(name),
            round = manifest.contains("android:roundIcon") || has(roundName),
            playStore = File(main, "ic_launcher-playstore.png").isFile,
        )
        return if (found.any) found else IconOptions.ALL
    }

    /** Returns the written files, relative to [displayRoot]. [main] is a module's `src/main` directory. */
    fun write(
        main: File,
        spec: IconSpec,
        displayRoot: File = main,
        format: ImageFormat = ImageFormat.Png,
        options: IconOptions = IconOptions.ALL,
    ): List<String> {
        val res = File(main, "res")
        val (name, roundName) = iconNames(main)

        val written = ArrayList<String>()
        fun rel(f: File) = f.relativeTo(displayRoot).path.replace(File.separatorChar, '/')
        /** Writes dir/name.<ext>, removing a same-named file of the other format so resources never clash. */
        fun image(dir: File, imageName: String, bmp: Bitmap, fmt: ImageFormat = format) {
            File(dir, "$imageName.png").delete()
            File(dir, "$imageName.webp").delete()
            val f = File(dir, "$imageName.${fmt.ext}")
            compress(bmp, f, fmt)
            written += rel(f)
        }
        fun text(f: File, body: String) {
            f.parentFile?.mkdirs()
            f.writeText(body)
            written += rel(f)
        }

        val masked = spec.bgKind != BgKind.None
        val bg = IconRenderer.background(spec)
        val fg = IconRenderer.foreground(spec)
        val mono = IconRenderer.foreground(spec, mono = true)
        try {
            if (options.adaptive) {
                image(File(res, "drawable-nodpi"), FG, fg)
                if (options.themed) image(File(res, "drawable-nodpi"), MONO, mono)
            }

            val header = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            val bgRef = if (!options.adaptive) "" else when (spec.bgKind) {
                BgKind.None -> {
                    File(res, "drawable/$BG.xml").delete()
                    File(res, "values/$BG.xml").delete()
                    "@android:color/transparent"
                }
                BgKind.Solid -> {
                    File(res, "drawable/$BG.xml").delete()
                    text(File(res, "values/$BG.xml"), header + "<resources>\n    <color name=\"$BG\">${hex(spec.bg1)}</color>\n</resources>\n")
                    "@color/$BG"
                }
                BgKind.Gradient -> {
                    File(res, "values/$BG.xml").delete()
                    text(
                        File(res, "drawable/$BG.xml"),
                        header + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">\n" +
                            "    <gradient android:type=\"linear\" android:angle=\"${spec.bgAngle}\" " +
                            "android:startColor=\"${hex(spec.bg1)}\" android:endColor=\"${hex(spec.bg2)}\" />\n</shape>\n",
                    )
                    "@drawable/$BG"
                }
            }

            if (options.legacy || options.round) for ((dpi, px) in DENSITIES) {
                val dir = File(res, "mipmap-$dpi")
                if (options.legacy) {
                    val square = IconRenderer.composite(bg, fg, spec.legacyShape, px, masked)
                    image(dir, name, square)
                    square.recycle()
                }
                if (options.round) {
                    val round = IconRenderer.composite(bg, fg, IconShape.Circle, px, masked)
                    image(dir, roundName, round)
                    round.recycle()
                }
            }

            if (options.adaptive) {
                val xml = header +
                    "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                    "    <background android:drawable=\"$bgRef\" />\n" +
                    "    <foreground android:drawable=\"@drawable/$FG\" />\n" +
                    (if (options.themed) "    <monochrome android:drawable=\"@drawable/$MONO\" />\n" else "") +
                    "</adaptive-icon>\n"
                val adaptiveDirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap-anydpi") }.orEmpty().toMutableList()
                if (adaptiveDirs.isEmpty()) adaptiveDirs += File(res, "mipmap-anydpi-v26")
                val xmlNames = if (options.round) listOf(name, roundName) else listOf(name)
                for (d in adaptiveDirs) for (n in xmlNames) text(File(d, "$n.xml"), xml)
            }

            // The Play Store listing icon is always a PNG.
            if (options.playStore) {
                val store = IconRenderer.composite(bg, fg, IconShape.Square, 512, masked)
                File(main, "ic_launcher-playstore.png").let { f ->
                    compress(store, f, ImageFormat.Png)
                    written += rel(f)
                }
                store.recycle()
            }
        } finally {
            bg.recycle(); fg.recycle(); mono.recycle()
        }
        return written
    }

    private val NOTIF_DENSITIES = listOf("mdpi" to 24, "hdpi" to 36, "xhdpi" to 48, "xxhdpi" to 72, "xxxhdpi" to 96)

    /** A valid, lowercase Android resource name; falls back to ic_stat_notify. */
    fun resourceName(raw: String): String {
        val n = raw.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        return when {
            n.isEmpty() -> "ic_stat_notify"
            n.first().isDigit() -> "ic_stat_$n"
            else -> n
        }
    }

    /** Notification small icons: res/drawable-<density>/<name>.<png|webp> at 24/36/48/72/96px. */
    fun writeNotification(main: File, spec: IconSpec, notif: NotifSpec, displayRoot: File = main, format: ImageFormat = ImageFormat.Png): List<String> {
        val name = resourceName(notif.name)
        val written = ArrayList<String>()
        for ((dpi, px) in NOTIF_DENSITIES) {
            val dir = File(main, "res/drawable-$dpi")
            dir.mkdirs()
            File(dir, "$name.xml").delete()
            File(dir, "$name.png").delete()
            File(dir, "$name.webp").delete()
            val f = File(dir, "$name.${format.ext}")
            val bmp = IconRenderer.notification(spec, notif, px)
            compress(bmp, f, format)
            bmp.recycle()
            written += f.relativeTo(displayRoot).path.replace(File.separatorChar, '/')
        }
        return written
    }

    /** Zips [dir]'s contents (paths relative to it) into [zip]. */
    fun zip(dir: File, zip: File) {
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    private fun hex(color: Int) = "#%08X".format(color)
}
