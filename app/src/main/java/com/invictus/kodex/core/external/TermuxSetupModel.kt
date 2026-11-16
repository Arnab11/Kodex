package com.invictus.kodex.core.external

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** A group of packages shown as chips on the Termux setup screen. */
data class PackageGroup(val titleRes: Int, val packages: List<TermuxPackage>)

/** [large] only drives a "large" label on the chip (big download), nothing else. */
data class TermuxPackage(val name: String, val large: Boolean = false)

data class TermuxPreset(val id: String, val label: String, val packages: Set<String>)

data class TermuxMirror(val id: String, val label: String, val url: String?)

/** Everything the user picked on the setup screen. */
data class SetupSelection(
    val packages: Set<String>,
    val extra: String,
    val mirrorId: String,
    val askStorage: Boolean,
    val upgrade: Boolean,
    val gitName: String,
    val gitEmail: String,
)

/** What one probe of Termux found out. */
data class TermuxProbe(
    val externalAllowed: Boolean,
    val storageOk: Boolean,
    val mirrorHost: String?,
    val installed: Set<String>,
)

object TermuxSetupModel {

    val groups: List<PackageGroup> = listOf(
        PackageGroup(
            com.invictus.kodex.R.string.termux_group_essentials,
            listOf("git", "openssh", "curl", "wget", "nano", "vim", "tmux", "zip", "unzip").map { TermuxPackage(it) },
        ),
        PackageGroup(
            com.invictus.kodex.R.string.termux_group_languages,
            listOf(
                TermuxPackage("python"), TermuxPackage("python-pip"), TermuxPackage("nodejs"),
                TermuxPackage("clang", large = true), TermuxPackage("make"), TermuxPackage("cmake"),
                TermuxPackage("golang", large = true), TermuxPackage("rust", large = true),
                TermuxPackage("openjdk-17", large = true), TermuxPackage("ruby"), TermuxPackage("php"),
            ),
        ),
        PackageGroup(
            com.invictus.kodex.R.string.termux_group_cli,
            listOf("ripgrep", "fd", "jq", "htop", "tree", "gh").map { TermuxPackage(it) },
        ),
    )

    val presets: List<TermuxPreset> = listOf(
        TermuxPreset("minimal", "Minimal", setOf("git", "openssh", "curl", "nano")),
        TermuxPreset("web", "Web dev", setOf("git", "openssh", "curl", "nano", "nodejs", "python", "ripgrep", "jq", "gh")),
        TermuxPreset("jvm", "Android / JVM", setOf("git", "openssh", "curl", "nano", "openjdk-17", "clang", "make", "zip", "unzip")),
        TermuxPreset("python", "Python", setOf("git", "openssh", "curl", "nano", "python", "python-pip")),
    )

    const val PRESET_CUSTOM = "custom"
    const val DEFAULT_PRESET = "minimal"

    val mirrors: List<TermuxMirror> = listOf(
        TermuxMirror("keep", "Keep current", null),
        TermuxMirror("cf", "Official (Cloudflare CDN)", "https://packages-cf.termux.dev/apt/termux-main"),
        TermuxMirror("official", "Official (packages.termux.dev)", "https://packages.termux.dev/apt/termux-main"),
        TermuxMirror("grimler", "Grimler (Sweden)", "https://grimler.se/termux/termux-main"),
        TermuxMirror("tuna", "Tsinghua (TUNA)", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"),
        TermuxMirror("bfsu", "BFSU", "https://mirrors.bfsu.edu.cn/termux/apt/termux-main"),
        TermuxMirror("ustc", "USTC", "https://mirrors.ustc.edu.cn/termux/apt/termux-main"),
    )

    private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.\\-]*")

    /** Free-text extras: only plain package names survive, so nothing typed can become shell code. */
    fun extraPackages(text: String): List<String> =
        text.split(Regex("[\\s,]+")).filter { it.matches(PACKAGE_NAME) }.distinct()

    fun allPackages(sel: SetupSelection): List<String> =
        (sel.packages.toList().sorted() + extraPackages(sel.extra)).distinct()

    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The script Termux runs in a visible session, so the user watches the progress.
     * Steps that were not asked for are left out; a marker file records that it ran to the end.
     */
    fun buildScript(sel: SetupSelection): String {
        val mirror = mirrors.firstOrNull { it.id == sel.mirrorId }?.url
        val pkgs = allPackages(sel)
        return buildString {
            appendLine("echo '== Kodex setup =='")
            appendLine("mkdir -p ~/.termux ~/.kodex")
            appendLine("grep -qs '^allow-external-apps=true' ~/.termux/termux.properties || echo 'allow-external-apps=true' >> ~/.termux/termux.properties")
            appendLine("termux-reload-settings")
            if (sel.askStorage) {
                appendLine("echo 'Allow storage access if Android asks...'")
                appendLine("termux-setup-storage; sleep 3")
            }
            if (mirror != null) {
                appendLine("echo ${sq("deb $mirror stable main")} > \"\$PREFIX/etc/apt/sources.list\"")
            }
            appendLine("pkg update -y")
            if (sel.upgrade) appendLine("pkg upgrade -y")
            if (pkgs.isNotEmpty()) appendLine("pkg install -y ${pkgs.joinToString(" ")}")
            if (sel.gitName.isNotBlank()) appendLine("git config --global user.name ${sq(sel.gitName.trim())}")
            if (sel.gitEmail.isNotBlank()) appendLine("git config --global user.email ${sq(sel.gitEmail.trim())}")
            appendLine("date > ~/.kodex/setup-done")
            appendLine("echo; echo 'Done. Go back to Kodex.'; read -r -p 'Press Enter to close... ' _")
        }
    }

    /** One command that reports external-apps access, storage access, current mirror and installed packages. */
    const val PROBE_COMMAND =
        "echo __ok; ls /storage/emulated/0 >/dev/null 2>&1 && echo __storage; " +
            "echo __mirror; grep -m1 '^deb' \"\$PREFIX/etc/apt/sources.list\" 2>/dev/null; " +
            "echo __pkgs; dpkg-query -W -f='\${Package}\\n' 2>/dev/null"

    fun parseProbe(stdout: String): TermuxProbe {
        val lines = stdout.lines().map { it.trim() }
        val mirrorIdx = lines.indexOf("__mirror")
        val pkgsIdx = lines.indexOf("__pkgs")
        val mirrorLine = if (mirrorIdx >= 0 && pkgsIdx > mirrorIdx + 1) lines[mirrorIdx + 1] else null
        val host = mirrorLine?.split(' ')?.getOrNull(1)?.substringAfter("://")?.substringBefore('/')
        return TermuxProbe(
            externalAllowed = "__ok" in lines,
            storageOk = "__storage" in lines,
            mirrorHost = host?.takeIf { it.isNotBlank() },
            installed = if (pkgsIdx >= 0) lines.drop(pkgsIdx + 1).filter { it.isNotBlank() }.toSet() else emptySet(),
        )
    }

    /** Pasted into Termux once; enables the intent that lets Kodex talk to it. */
    const val BOOTSTRAP_COMMAND =
        "mkdir -p ~/.termux && (grep -qs '^allow-external-apps=true' ~/.termux/termux.properties || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings"
}

/** Persisted setup choices and the "setup done" flag the Terminal menu item checks. */
object TermuxSetupPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("termux_setup", Context.MODE_PRIVATE)

    private var enabledState: MutableState<Boolean>? = null

    /** Whether the terminal features (Terminal, Run command, Run file) are switched on. Off by default; observable from Compose. */
    fun enabled(context: Context): MutableState<Boolean> =
        enabledState ?: mutableStateOf(prefs(context).getBoolean("enabled", false)).also { enabledState = it }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("enabled", value).apply()
        enabled(context).value = value
    }

    /**
     * Whether Termux has answered a Kodex call before. Every call to a Termux that has not enabled
     * external apps makes Termux post an error notification, so Kodex only calls it on its own
     * (probe, `kodex` CLI install) once this is known to be true.
     */
    fun externalOk(context: Context): Boolean = prefs(context).getBoolean("externalOk", false)
    fun setExternalOk(context: Context, v: Boolean) = prefs(context).edit().putBoolean("externalOk", v).apply()

    fun load(context: Context): SetupSelection {
        val p = prefs(context)
        val preset = TermuxSetupModel.presets.first { it.id == TermuxSetupModel.DEFAULT_PRESET }
        return SetupSelection(
            packages = p.getString("packages", null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: preset.packages,
            extra = p.getString("extra", "").orEmpty(),
            mirrorId = p.getString("mirror", "keep").orEmpty(),
            askStorage = p.getBoolean("storage", true),
            upgrade = p.getBoolean("upgrade", false),
            gitName = p.getString("gitName", "").orEmpty(),
            gitEmail = p.getString("gitEmail", "").orEmpty(),
        )
    }

    fun save(context: Context, sel: SetupSelection) {
        prefs(context).edit()
            .putString("packages", sel.packages.joinToString(","))
            .putString("extra", sel.extra)
            .putString("mirror", sel.mirrorId)
            .putBoolean("storage", sel.askStorage)
            .putBoolean("upgrade", sel.upgrade)
            .putString("gitName", sel.gitName)
            .putString("gitEmail", sel.gitEmail)
            .apply()
    }

    fun overlayAcknowledged(context: Context): Boolean = prefs(context).getBoolean("overlayAck", false)
    fun setOverlayAcknowledged(context: Context, v: Boolean) = prefs(context).edit().putBoolean("overlayAck", v).apply()
}
