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
object IconExporter {
    private val DENSITIES = listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
    private const val FG = "ic_launcher_kodex_fg"
    private const val BG = "ic_launcher_kodex_bg"
    private const val MONO = "ic_launcher_kodex_mono"

    /** Returns the written files, relative to [displayRoot]. [main] is a module's `src/main` directory. */
    fun write(main: File, spec: IconSpec, displayRoot: File = main): List<String> {
        val res = File(main, "res")
        val manifest = File(main, "AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty()
        val name = Regex("""android:icon="@mipmap/([A-Za-z0-9_]+)"""").find(manifest)?.groupValues?.get(1) ?: "ic_launcher"
        val roundName = Regex("""android:roundIcon="@mipmap/([A-Za-z0-9_]+)"""").find(manifest)?.groupValues?.get(1) ?: "${name}_round"

        val written = ArrayList<String>()
        fun rel(f: File) = f.relativeTo(displayRoot).path.replace(File.separatorChar, '/')
        fun png(f: File, bmp: Bitmap) {
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            written += rel(f)
        }
        fun text(f: File, body: String) {
            f.parentFile?.mkdirs()
            f.writeText(body)
            written += rel(f)
        }

        val bg = IconRenderer.background(spec)
        val fg = IconRenderer.foreground(spec)
        val mono = IconRenderer.foreground(spec, mono = true)
        try {
            png(File(res, "drawable-nodpi/$FG.png"), fg)
            png(File(res, "drawable-nodpi/$MONO.png"), mono)

            val bgRef: String
            val header = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            if (spec.bgKind == BgKind.Solid) {
                File(res, "drawable/$BG.xml").delete()
                text(File(res, "values/$BG.xml"), header + "<resources>\n    <color name=\"$BG\">${hex(spec.bg1)}</color>\n</resources>\n")
                bgRef = "@color/$BG"
            } else {
                File(res, "values/$BG.xml").delete()
                text(
                    File(res, "drawable/$BG.xml"),
                    header + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">\n" +
                        "    <gradient android:type=\"linear\" android:angle=\"${spec.bgAngle}\" " +
                        "android:startColor=\"${hex(spec.bg1)}\" android:endColor=\"${hex(spec.bg2)}\" />\n</shape>\n",
                )
                bgRef = "@drawable/$BG"
            }

            for ((dpi, px) in DENSITIES) {
                val dir = File(res, "mipmap-$dpi")
                for (n in listOf(name, roundName)) File(dir, "$n.webp").delete()
                val square = IconRenderer.composite(bg, fg, spec.legacyShape, px)
                png(File(dir, "$name.png"), square)
                square.recycle()
                val round = IconRenderer.composite(bg, fg, IconShape.Circle, px)
                png(File(dir, "$roundName.png"), round)
                round.recycle()
            }

            val xml = header +
                "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "    <background android:drawable=\"$bgRef\" />\n" +
                "    <foreground android:drawable=\"@drawable/$FG\" />\n" +
                "    <monochrome android:drawable=\"@drawable/$MONO\" />\n" +
                "</adaptive-icon>\n"
            val adaptiveDirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap-anydpi") }.orEmpty().toMutableList()
            if (adaptiveDirs.isEmpty()) adaptiveDirs += File(res, "mipmap-anydpi-v26")
            for (d in adaptiveDirs) for (n in listOf(name, roundName)) text(File(d, "$n.xml"), xml)

            val store = IconRenderer.composite(bg, fg, IconShape.Square, 512)
            png(File(main, "ic_launcher-playstore.png"), store)
            store.recycle()
        } finally {
            bg.recycle(); fg.recycle(); mono.recycle()
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
