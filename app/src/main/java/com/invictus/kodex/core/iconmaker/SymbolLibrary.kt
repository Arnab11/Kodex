package com.invictus.kodex.core.iconmaker

import android.content.Context
import android.graphics.Path
import android.util.LruCache
import androidx.core.graphics.PathParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Glyph data bundled as assets (24x24 viewBox path data), parsed lazily off the main thread:
 *  - iconmaker/symbols.tsv: Material Symbols (rounded, filled), `name<TAB>path`
 *  - iconmaker/brands.tsv: brand logos from Simple Icons (CC0), `slug<TAB>hex<TAB>title<TAB>path`; their
 *    names are stored as "@slug" so they never collide with Material names.
 */
object SymbolLibrary {
    class Symbol(
        val name: String,
        val pathData: String,
        val title: String = name,
        /** Brand color as RGB (no alpha), 0 for Material symbols. */
        val brandColor: Int = 0,
    ) {
        val isBrand: Boolean get() = name.startsWith("@")
        val searchKey: String = (name.removePrefix("@") + " " + title).lowercase()
    }

    @Volatile private var materials: List<Symbol>? = null
    @Volatile private var brands: List<Symbol>? = null
    private val byName = HashMap<String, Symbol>()
    private val paths = LruCache<String, Path>(700)
    private val failed = HashSet<String>()

    suspend fun load(context: Context): List<Symbol> {
        materials?.let { return it }
        return withContext(Dispatchers.IO) {
            val list = ArrayList<Symbol>(3600)
            context.assets.open("iconmaker/symbols.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val i = line.indexOf('\t')
                    if (i > 0) list += Symbol(line.substring(0, i), line.substring(i + 1))
                }
            }
            register(list)
            materials = list
            list
        }
    }

    suspend fun loadBrands(context: Context): List<Symbol> {
        brands?.let { return it }
        return withContext(Dispatchers.IO) {
            val list = ArrayList<Symbol>(200)
            context.assets.open("iconmaker/brands.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t', limit = 4)
                    if (p.size < 4) continue
                    list += Symbol("@" + p[0], p[3], p[2], p[1].toIntOrNull(16) ?: 0)
                }
            }
            register(list)
            brands = list
            list
        }
    }

    private fun register(list: List<Symbol>) {
        synchronized(byName) { for (s in list) byName[s.name] = s }
    }

    fun get(name: String): Symbol? = synchronized(byName) { byName[name] }

    /** The glyph in a 24x24 box, or null when unknown / not loaded yet. Treat the result as read-only. */
    fun path(name: String): Path? {
        paths.get(name)?.let { return it }
        val symbol = get(name) ?: return null
        if (synchronized(failed) { name in failed }) return null
        // PathParser throws on data it cannot read; a bad glyph must never take the UI down.
        val p = runCatching { PathParser.createPathFromPathData(symbol.pathData) }.getOrNull()
        if (p == null) {
            synchronized(failed) { failed += name }
            return null
        }
        paths.put(name, p)
        return p
    }
}
