package com.invictus.kodex.core.iconmaker

import android.content.Context
import android.graphics.Path
import android.util.LruCache
import androidx.core.graphics.PathParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Material Symbols (rounded, filled; 24x24 viewBox) bundled as assets/iconmaker/symbols.tsv, one
 * `name<TAB>pathData` line per icon. Parsed lazily off the main thread; paths are parsed on first use.
 */
object SymbolLibrary {
    class Symbol(val name: String, val pathData: String)

    @Volatile private var all: List<Symbol>? = null
    private val byName = HashMap<String, Symbol>()
    private val paths = LruCache<String, Path>(700)
    private val failed = HashSet<String>()

    suspend fun load(context: Context): List<Symbol> {
        all?.let { return it }
        return withContext(Dispatchers.IO) {
            val list = ArrayList<Symbol>(3600)
            context.assets.open("iconmaker/symbols.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val i = line.indexOf('\t')
                    if (i > 0) list += Symbol(line.substring(0, i), line.substring(i + 1))
                }
            }
            synchronized(byName) { for (s in list) byName[s.name] = s }
            all = list
            list
        }
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
