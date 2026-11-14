package io.github.ccch1mneyyy.dsh_app.root

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.TimeUnit

/**
 * The root side of [RootBroker]. Started as uid 0 through
 * `su -c "app_process ... RootBrokerMain"`; reads one JSON request per line on
 * stdin and writes one JSON response per line on stdout.
 *
 * It never interprets a path outside the roots the app declared with
 * `--allow`, so a compromised renderer cannot turn `fs.write` into an
 * arbitrary-file primitive.
 */
object RootBrokerMain {
    private val allowed = mutableListOf<String>()
    private lateinit var out: PrintWriter
    private val lock = Any()

    @JvmStatic
    fun main(args: Array<String>) {
        var i = 0
        while (i < args.size) {
            if (args[i] == "--allow" && i + 1 < args.size) allowed += File(args[i + 1]).canonicalPath
            i++
        }
        out = PrintWriter(System.out, true)
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            val request = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val id = request.optInt("id", -1)
            val op = request.optString("op")
            val argsJson = request.optJSONObject("args") ?: JSONObject()
            val response = try {
                JSONObject().put("id", id).put("ok", true).put("value", dispatch(op, argsJson))
            } catch (error: Exception) {
                JSONObject().put("id", id).put("ok", false)
                    .put("error", error.javaClass.simpleName + ": " + (error.message ?: ""))
            }
            synchronized(lock) {
                out.println(response.toString())
                out.flush()
            }
        }
    }

    private fun dispatch(op: String, args: JSONObject): Any = when (op) {
        "ping" -> JSONObject().put("revision", 1).put("uid", android.os.Process.myUid())
        "probe" -> probe()
        "exec" -> exec(args.getString("command"), args.optLong("timeoutMs", 60_000))
        "fs.read" -> JSONObject().put("text", read(args.getString("path"), args.optInt("lines", 0)))
        "fs.write" -> JSONObject().put("ok", write(args.getString("path"), args.getString("text"), args.optString("mode", "0644")))
        "fs.append" -> JSONObject().put("ok", append(args.getString("path"), args.getString("line")))
        "fs.remove" -> JSONObject().put("ok", remove(args.getString("path")))
        "fs.list" -> JSONObject().put("names", list(args.getString("path")))
        "signal" -> JSONObject().put("ok", signal(args.getInt("pid"), args.optString("signal", "TERM")))
        "cleanup" -> JSONObject().put("ok", cleanup())
        else -> throw IllegalArgumentException("unknown op $op")
    }

    private fun probe(): JSONObject {
        val enforce = runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()
        return JSONObject()
            .put("uid", android.os.Process.myUid())
            .put("pid", android.os.Process.myPid())
            .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull())
            .put("selinux", enforce)
            .put("revision", 1)
    }

    private fun exec(command: String, timeoutMs: Long): JSONObject {
        val level = RootPolicy.classify(command)
        require(level != RootPolicy.Level.forbidden) { "forbidden by root policy" }
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val pump = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < 256 * 1024) output.append(line).append('\n')
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs.coerceIn(1_000, 120_000), TimeUnit.MILLISECONDS)
        if (!finished) runCatching { process.destroyForcibly() }
        runCatching { pump.join(1000) }
        return JSONObject()
            .put("code", if (finished) process.exitValue() else -1)
            .put("output", synchronized(output) { output.toString() })
            .put("timedOut", !finished)
    }

    private fun check(path: String): File {
        val file = File(path)
        val canonical = if (file.exists()) file.canonicalFile else {
            val parent = file.parentFile ?: throw IllegalArgumentException("no parent")
            File(parent.canonicalFile, file.name)
        }
        val ok = allowed.any { root -> canonical.path == root || canonical.path.startsWith("$root/") }
        require(ok) { "path outside allowed roots: ${canonical.path}" }
        return canonical
    }

    private fun read(path: String, lines: Int): String {
        val file = check(path)
        require(file.isFile) { "not a file" }
        if (lines <= 0) return file.readText()
        return file.useLines { all -> all.toList().takeLast(lines).joinToString("\n") }
    }

    private fun write(path: String, text: String, mode: String): Boolean {
        val file = check(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        runCatching { Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", mode, file.path)).waitFor() }
        return true
    }

    private fun append(path: String, line: String): Boolean {
        val file = check(path)
        file.parentFile?.mkdirs()
        RootAudit.rotateIfNeeded(file)
        file.appendText(line + "\n")
        return true
    }

    private fun remove(path: String): Boolean {
        val file = check(path)
        return file.deleteRecursively()
    }

    private fun list(path: String): List<String> {
        val file = check(path)
        return file.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    private fun signal(pid: Int, signal: String): Boolean {
        require(pid > 1) { "bad pid" }
        val flag = when (signal.uppercase()) {
            "TERM", "SIGTERM", "15" -> "-TERM"
            "KILL", "SIGKILL", "9" -> "-KILL"
            "INT", "SIGINT", "2" -> "-INT"
            else -> throw IllegalArgumentException("unsupported signal $signal")
        }
        // Negative pid: the whole process group (helper is a session leader).
        val group = ProcessBuilder("/system/bin/sh", "-c", "kill $flag -$pid 2>/dev/null || kill $flag $pid")
            .redirectErrorStream(true).start()
        return group.waitFor(5, TimeUnit.SECONDS) && group.exitValue() == 0
    }

    private fun cleanup(): Boolean {
        if (!File(RootHelper.DIR).canonicalPath.startsWith("/data/adb/")) return false
        File(RootHelper.DIR, "audit.log").delete()
        File(RootHelper.DIR, "audit.log.1").delete()
        File(RootHelper.DIR, "run").deleteRecursively()
        return true
    }
}
