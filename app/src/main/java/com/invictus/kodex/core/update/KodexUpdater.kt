package com.invictus.kodex.core.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.invictus.kodex.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Which releases the updater looks at. Stable = published releases; Preview = the newest of everything (pre-releases too). */
enum class UpdateChannel { STABLE, PREVIEW }

data class UpdateAsset(val name: String, val url: String, val size: Long)

/** A newer release that has an APK for this device. [sha256] comes from the checksums block in the release notes, if any. */
data class UpdateInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val pageUrl: String,
    val prerelease: Boolean,
    val asset: UpdateAsset,
    val sha256: String?,
)

sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Available(val info: UpdateInfo) : CheckResult
    data class Failed(val message: String) : CheckResult
}

/**
 * Version ordering identical to .github/scripts/version-from-tag.sh, so "newer" means exactly what CI says:
 * code = (MAJOR*10000 + MINOR*100 + PATCH) * 100 + stage (stable 99, alpha 0.., beta 30.., rc 60..).
 */
internal object UpdateVersion {
    private val TAG = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?$""")
    private val LAST_NUMBER = Regex("""(\d+)[^0-9]*$""")

    fun code(name: String): Long? {
        val m = TAG.matchEntire(name.trim()) ?: return null
        val (major, minor, patch) = m.groupValues.subList(1, 4).map { it.toLong() }
        val suffix = m.groupValues[4]
        val stage = if (suffix.isEmpty()) {
            99L
        } else {
            val n = (LAST_NUMBER.find(suffix)?.groupValues?.get(1)?.toLongOrNull() ?: 0L).coerceAtMost(29L)
            when {
                suffix.startsWith("alpha", true) -> n
                suffix.startsWith("beta", true) -> 30 + n
                suffix.startsWith("rc", true) -> 60 + n
                else -> 30 + n
            }
        }
        return (major * 10_000 + minor * 100 + patch) * 100 + stage
    }
}

object UpdateChecker {
    private const val TAG = "KodexUpdate"
    private const val REPO = "Utsavrajputt/Kodex"
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=20"

    /** Newest successful run of .github/workflows/preview.yml (no token needed, the repo is public). */
    private const val PREVIEW_RUNS_URL =
        "https://api.github.com/repos/$REPO/actions/workflows/preview.yml/runs?status=success&per_page=1"

    /** GitHub Pages site; preview APKs live under /previews/r<run>/ (see scripts/stage_pages.py). */
    const val SITE_URL = "https://utsavrajputt.github.io/Kodex"
    private val CHECKSUM = Regex("""([0-9a-fA-F]{64})\s+\*?(\S+\.apk)""")

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Asks GitHub for the newest release of [channel] and compares it with [installedVersionName]. Blocking: call off the main thread. */
    fun check(channel: UpdateChannel, installedVersionName: String): CheckResult {
        // Preview: the newest successful preview.yml run (APKs on Pages) is the primary feed. Without one
        // (no run yet, GitHub unreachable) fall through to hand-cut pre-releases below.
        if (channel == UpdateChannel.PREVIEW) checkPreviewRun()?.let { return it }
        val installed = UpdateVersion.code(installedVersionName)
            ?: return CheckResult.Failed("Unknown installed version: $installedVersionName")
        val body = try {
            http.newCall(
                Request.Builder().url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build(),
            ).execute().use { r ->
                if (!r.isSuccessful) return CheckResult.Failed("GitHub answered ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            return CheckResult.Failed(e.message ?: "No connection")
        }
        val releases = try {
            JSONArray(body)
        } catch (e: Exception) {
            return CheckResult.Failed("Unexpected answer from GitHub")
        }

        var best: Pair<Long, UpdateInfo>? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val prerelease = r.optBoolean("prerelease")
            if (channel == UpdateChannel.STABLE && prerelease) continue
            val tag = r.optString("tag_name")
            val code = UpdateVersion.code(tag) ?: continue
            if (code <= installed || (best != null && code <= best.first)) continue
            val notes = r.optString("body")
            val asset = pickAsset(r.optJSONArray("assets")) ?: continue
            best = code to UpdateInfo(
                tag = tag,
                version = tag.removePrefix("v"),
                notes = notes,
                pageUrl = r.optString("html_url"),
                prerelease = prerelease,
                asset = asset,
                sha256 = CHECKSUM.findAll(notes).firstOrNull { it.groupValues[2] == asset.name }?.groupValues?.get(1)?.lowercase(),
            )
        }
        return best?.let { CheckResult.Available(it.second) } ?: CheckResult.UpToDate
    }

    // ---- Preview builds (Pages) ------------------------------------------------------------------------

    private fun getJson(url: String, timeoutMs: Long = 0L): String? = try {
        val client = if (timeoutMs > 0) http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else http
        client.newCall(
            Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build(),
        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: IOException) {
        null
    }

    /**
     * Result for the Preview channel from the newest successful preview.yml run, or null when that feed is
     * unavailable. A run only succeeds after its APKs were deployed to Pages, so the files exist once it is listed.
     */
    private fun checkPreviewRun(): CheckResult? {
        val run = runCatching {
            JSONObject(getJson(PREVIEW_RUNS_URL) ?: return null).optJSONArray("workflow_runs")?.optJSONObject(0)
        }.getOrNull() ?: return null
        val number = run.optInt("run_number", 0)
        if (number <= 0) return null
        val sha = run.optString("head_sha")
        if (number <= BuildConfig.PREVIEW_RUN) return CheckResult.UpToDate
        if (currentBuildCoversPreview(sha, run.optString("updated_at"))) return CheckResult.UpToDate

        val abis = Build.SUPPORTED_ABIS.filter { it == "arm64-v8a" || it == "armeabi-v7a" } + "universal"
        val abi = abis.first()
        val name = "Kodex-$abi-preview-r$number.apk"
        val base = "$SITE_URL/previews/r$number"
        val notes = previewNotes(sha)
        return CheckResult.Available(
            UpdateInfo(
                tag = "preview-r$number",
                version = "preview r$number",
                notes = notes,
                pageUrl = run.optString("html_url").ifBlank { "https://github.com/$REPO/actions/workflows/preview.yml" },
                prerelease = true,
                asset = UpdateAsset(name, "$base/$name", 0L),
                sha256 = previewChecksum("$base/SHA256SUMS", name),
            ),
        )
    }

    /**
     * True when this build is at or past the preview, i.e. offering it would be a downgrade. Builds that did not
     * come from preview.yml have PREVIEW_RUN == 0, so the run number alone calls every preview newer. For those:
     * ask GitHub how our commit relates to the preview's ("ahead" / "identical" = covered, "behind" = really newer);
     * if that can't be answered, a build compiled after the preview run finished is newer.
     */
    private fun currentBuildCoversPreview(previewSha: String, updatedAt: String): Boolean {
        if (BuildConfig.PREVIEW_RUN > 0) return false
        val own = BuildConfig.GIT_SHA.takeIf { it.isNotBlank() }
        if (previewSha.isNotBlank() && own != null) {
            if (previewSha.equals(own, ignoreCase = true)) return true
            val status = runCatching {
                JSONObject(getJson("https://api.github.com/repos/$REPO/compare/$previewSha...$own", 6_000) ?: "{}").optString("status")
            }.getOrDefault("")
            when (status) {
                "ahead", "identical" -> return true
                "behind" -> return false
            }
        }
        val at = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(updatedAt)?.time
        }.getOrNull() ?: return false
        Log.i(TAG, "preview built at $at, this build at ${BuildConfig.BUILD_TIME_MS}")
        return BuildConfig.BUILD_TIME_MS > at
    }

    /** "What's new" for a preview: the commit subjects since this build (or the latest ones), best effort. */
    private fun previewNotes(headSha: String): String {
        val head = if (headSha.isNotBlank()) "Automatic preview from ${headSha.take(7)}." else "Automatic preview build."
        if (headSha.isBlank()) return head
        val own = BuildConfig.GIT_SHA.takeIf { it.isNotBlank() && !it.equals(headSha, ignoreCase = true) }
        val messages = runCatching {
            val fromCompare = own?.let {
                getJson("https://api.github.com/repos/$REPO/compare/$it...$headSha", 6_000)
                    ?.let { b -> JSONObject(b).optJSONArray("commits") }
                    ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optJSONObject("commit")?.optString("message") }.asReversed() }
            }
            fromCompare ?: getJson("https://api.github.com/repos/$REPO/commits?sha=$headSha&per_page=10", 6_000)
                ?.let { JSONArray(it) }
                ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optJSONObject("commit")?.optString("message") } }
        }.getOrNull().orEmpty()
        val subjects = messages.map { it.lineSequence().first().trim() }.filter { it.isNotEmpty() }.take(12)
        return if (subjects.isEmpty()) head else head + "\n\n" + subjects.joinToString("\n") { "- $it" }
    }

    /** SHA-256 of [name] from the preview's SHA256SUMS file on Pages; null when it can't be read (no check then). */
    private fun previewChecksum(url: String, name: String): String? = try {
        http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null
                else CHECKSUM.findAll(r.body?.string().orEmpty()).firstOrNull { it.groupValues[2] == name }?.groupValues?.get(1)?.lowercase()
            }
    } catch (e: IOException) {
        null
    }

    /** The APK for this phone's best ABI, else the universal one. Assets are named kodex-<tag>-<abi>.apk. */
    private fun pickAsset(assets: JSONArray?): UpdateAsset? {
        assets ?: return null
        val all = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk") }
        fun find(abi: String): JSONObject? = all.firstOrNull { it.optString("name").endsWith("-$abi.apk") }
        val chosen = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { find(it) } ?: find("universal") ?: return null
        return UpdateAsset(chosen.optString("name"), chosen.optString("browser_download_url"), chosen.optLong("size"))
    }
}

/** Resumable download into the app cache (a .part file keeps what was already fetched), with a SHA-256 check when known. */
internal object UpdateDownloader {
    fun dir(context: Context): File = File(context.cacheDir, "gh_apk/kodex-update").apply { mkdirs() }

    /** Blocking. [onProgress] gets (bytes so far, total or -1). Throws IOException on failure. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Long, Long) -> Unit): File {
        val dir = dir(context)
        dir.listFiles()?.filter { it.name != info.asset.name && it.name != info.asset.name + ".part" }?.forEach { it.delete() }
        val target = File(dir, info.asset.name)
        if (target.exists() && verified(target, info)) return target
        target.delete()

        val part = File(dir, info.asset.name + ".part")
        var have = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url(info.asset.url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        UpdateChecker.http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val resumed = r.code == 206
            if (!resumed) have = 0L
            val total = (r.body?.contentLength() ?: -1L).let { if (it > 0) it + have else info.asset.size }
            val body = r.body ?: throw IOException("Empty download")
            RandomAccessFile(part, "rw").use { out ->
                if (!resumed) out.setLength(0)
                out.seek(have)
                val buf = ByteArray(64 * 1024)
                var done = have
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        if (info.asset.size > 0 && part.length() != info.asset.size) throw IOException("Download incomplete, try again")
        if (!verified(part, info)) {
            part.delete()
            throw IOException("Checksum does not match, download discarded")
        }
        if (!part.renameTo(target)) throw IOException("Could not save the update")
        return target
    }

    private fun verified(file: File, info: UpdateInfo): Boolean {
        if (info.asset.size > 0 && file.length() != info.asset.size) return false
        val expected = info.sha256 ?: return true
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == expected
    }
}
