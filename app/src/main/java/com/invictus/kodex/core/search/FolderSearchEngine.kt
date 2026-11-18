package com.invictus.kodex.core.search

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Open Project sheet's folder search: naam se fuzzy match over the whole [root].
 *
 * Fast path: the folder tree is scanned ONCE (parallel, level by level) into an in-memory index that is
 * shared process-wide; every keystroke then only filters that list, so results are instant. The index is
 * warmed up when the sheet opens ([warmUp]), served stale-while-revalidate after changes ([invalidate])
 * and rebuilt in the background -- the old per-keystroke full disk walk is gone.
 */
class FolderSearchEngine {
    data class Result(
        val file: File,
        val name: String,
        val relativePath: String,
        val score: Int,
        val matchedIndices: List<Int>,
    )

    suspend fun search(
        root: File,
        query: String,
        showHidden: Boolean = false,
    ): List<Result> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val index = indexFor(root, showHidden)
        return withContext(Dispatchers.Default) {
            val lower = q.lowercase()
            val hits = ArrayList<Result>()
            var i = 0
            for (e in index.entries) {
                if ((++i and 0x3FF) == 0) ensureActive() // a newer query cancels this one
                if (!isSubsequence(lower, e.lower)) continue
                val m = FuzzyMatcher.match(q, e.name) ?: continue
                hits.add(Result(e.file, e.name, e.rel, m.score, m.indices))
            }
            hits.sortByDescending { it.score }
            hits.take(MAX_RESULTS)
        }
    }

    /** Start (or refresh) the index in the background so the first keystroke is already instant. */
    fun warmUp(root: File, showHidden: Boolean = false) {
        scope.launch { runCatching { indexFor(root, showHidden) } }
    }

    private class Entry(val file: File, val name: String, val lower: String, val rel: String)

    private class Index(val key: String, val entries: List<Entry>, val builtAt: Long) {
        @Volatile var stale = false
    }

    private class Chunk(val entries: List<Entry>, val dirs: List<File>)

    private suspend fun indexFor(root: File, showHidden: Boolean): Index {
        val key = root.path + "|" + showHidden
        val cached: Index?
        val job: Deferred<Index>
        synchronized(lock) {
            val c = cache?.takeIf { it.key == key }
            if (c != null && !c.stale && System.currentTimeMillis() - c.builtAt < MAX_AGE_MS) return c
            cached = c
            val running = building?.takeIf { buildingKey == key }
            job = running ?: scope.async { build(root, showHidden, key) }.also { j ->
                building = j
                buildingKey = key
                j.invokeOnCompletion { synchronized(lock) { if (building === j) building = null } }
            }
        }
        // Stale-while-revalidate: an old index answers right away, the rebuild feeds the next query.
        return cached ?: job.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun build(root: File, showHidden: Boolean, key: String): Index = coroutineScope {
        val dispatcher = Dispatchers.IO.limitedParallelism(PARALLELISM)
        val rootPath = root.path
        val out = ArrayList<Entry>(4096)
        var frontier: List<File> = listOf(root)
        var depth = 0
        while (frontier.isNotEmpty() && depth < MAX_DEPTH && out.size < MAX_ENTRIES) {
            ensureActive()
            val size = maxOf(1, frontier.size / (PARALLELISM * 2) + 1)
            val parts = frontier.chunked(size).map { slice ->
                async(dispatcher) { scan(slice, rootPath, showHidden) }
            }.awaitAll()
            val next = ArrayList<File>()
            for (p in parts) {
                out.addAll(p.entries)
                next.addAll(p.dirs)
            }
            frontier = next
            depth++
        }
        Index(key, out, System.currentTimeMillis()).also {
            synchronized(lock) { cache = it }
        }
    }

    private fun scan(dirs: List<File>, rootPath: String, showHidden: Boolean): Chunk {
        val entries = ArrayList<Entry>()
        val subDirs = ArrayList<File>()
        for (dir in dirs) {
            val children = dir.listFiles() ?: continue
            val inAndroid = dir.name == "Android"
            for (child in children) {
                val name = child.name
                if (!showHidden && name.startsWith(".")) continue
                if (name in SKIPPED_DIRS) continue
                // Android/data and Android/obb: scoped storage lists them badly and they are heavy.
                if (inAndroid && name in ANDROID_SKIPPED) continue
                if (!child.isDirectory) continue
                entries.add(Entry(child, name, name.lowercase(), child.path.removePrefix(rootPath).trim('/', ' ')))
                subDirs.add(child)
            }
        }
        return Chunk(entries, subDirs)
    }

    companion object {
        private val SKIPPED_DIRS = setOf(".git", "build", "node_modules", ".gradle", ".idea")
        private val ANDROID_SKIPPED = setOf("data", "obb")
        const val MAX_RESULTS = 200
        private const val PARALLELISM = 8
        private const val MAX_DEPTH = 24
        private const val MAX_ENTRIES = 250_000
        private const val MAX_AGE_MS = 3 * 60_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private var cache: Index? = null
        private var building: Deferred<Index>? = null
        private var buildingKey: String? = null

        /** A folder was created / renamed / deleted: keep serving the old index, but rebuild it on the next search. */
        fun invalidate() {
            synchronized(lock) { cache?.stale = true }
        }

        private fun isSubsequence(q: String, c: String): Boolean {
            var ci = 0
            for (ch in q) {
                ci = c.indexOf(ch, ci)
                if (ci < 0) return false
                ci++
            }
            return true
        }
    }
}
