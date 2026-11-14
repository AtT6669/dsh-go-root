package io.github.ccch1mneyyy.dsh_app.root

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The single place that starts `su`. Every privileged operation in the root
 * build goes through here (or through [RootBroker], which is one long-lived
 * `su` child): the app process itself keeps its normal uid.
 */
object RootExec {
    data class Result(val code: Int, val output: String, val timedOut: Boolean = false) {
        val ok: Boolean get() = !timedOut && code == 0
    }

    /** `su -c` takes exactly one command string on Magisk, KernelSU and APatch. */
    fun su(command: String, timeoutMs: Long = 30_000): Result {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return Result(127, error.javaClass.simpleName + ": " + (error.message ?: ""))
        }
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < 256 * 1024) output.append(line).append('\n')
                        }
                    }
                }
            }
        }.apply { isDaemon = true; name = "dsh-su-read"; start() }
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            runCatching { process.destroyForcibly() }
            return Result(-1, synchronized(output) { output.toString() }, timedOut = true)
        }
        runCatching { reader.join(1500) }
        return Result(process.exitValue(), synchronized(output) { output.toString() })
    }

    /** Quotes one argument for the single command string `su -c` receives. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun command(argv: List<String>): String = argv.joinToString(" ") { quote(it) }

    /** Reads a small root-owned file through `su` when the app cannot read it itself. */
    fun readRootFile(path: String): String? {
        val result = su("cat ${quote(path)}")
        return if (result.ok) result.output else null
    }

    fun writeRootFile(path: String, content: String, mode: String = "0644"): Boolean {
        val dir = File(path).parentFile?.absolutePath ?: return false
        val temp = "$path.dsh-tmp"
        val result = su(
            "mkdir -p ${quote(dir)} && printf '%s' ${quote(content)} > ${quote(temp)}" +
                " && mv ${quote(temp)} ${quote(path)} && chmod $mode ${quote(path)}",
        )
        return result.ok
    }
}
