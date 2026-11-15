package com.invictus.kodex.core.external

import java.io.File

/** Which interpreter runs which file, and the Termux script that does it. */
object TermuxRunFile {

    private class Runner(val bin: String, val pkg: String, val template: (file: String) -> String)

    private const val OUT = "\"\$TMPDIR/kodex_run\""

    private val runners: Map<String, Runner> = buildMap {
        fun add(exts: List<String>, r: Runner) = exts.forEach { put(it, r) }
        add(listOf("py"), Runner("python", "python") { "python $it" })
        add(listOf("js", "mjs", "cjs"), Runner("node", "nodejs") { "node $it" })
        add(listOf("sh", "bash"), Runner("bash", "bash") { "bash $it" })
        add(listOf("rb"), Runner("ruby", "ruby") { "ruby $it" })
        add(listOf("php"), Runner("php", "php") { "php $it" })
        add(listOf("go"), Runner("go", "golang") { "go run $it" })
        add(listOf("java"), Runner("java", "openjdk-17") { "java $it" })
        add(listOf("c"), Runner("clang", "clang") { "clang $it -o $OUT && $OUT" })
        add(listOf("cpp", "cc", "cxx"), Runner("clang++", "clang") { "clang++ $it -o $OUT && $OUT" })
        add(listOf("rs"), Runner("rustc", "rust") { "rustc $it -o $OUT && $OUT" })
    }

    private fun runnerFor(file: File): Runner? = runners[file.extension.lowercase()]

    fun isRunnable(file: File): Boolean = runnerFor(file) != null

    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Script for a visible Termux session (cwd = the file's folder): runs the file, shows the exit
     * code and waits for Enter so the output stays readable. A missing interpreter says which
     * package to install instead of failing with a bare "command not found".
     */
    fun script(file: File): String? {
        val runner = runnerFor(file) ?: return null
        val cmd = runner.template(sq(file.name))
        return buildString {
            appendLine("command -v ${runner.bin} >/dev/null 2>&1 || { echo '${runner.bin} is not installed. Run: pkg install ${runner.pkg}'; read -r _; exit 1; }")
            appendLine("echo ${sq("$ $cmd")}")
            appendLine(cmd)
            appendLine("code=\$?")
            appendLine("echo; echo \"[exit \$code] Press Enter to close\"; read -r _")
        }
    }
}
