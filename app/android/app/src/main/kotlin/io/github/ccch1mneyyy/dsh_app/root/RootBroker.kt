package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for the long-lived root broker. One `su` prompt covers engine
 * supervision, host file operations and agent commands; the app process never
 * gains root itself.
 */
class RootBroker private constructor(private val context: Context) {
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()

    @Volatile private var process: Process? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var alive = false
    @Volatile var lastError: String? = null
        private set

    /** Last lines the uid-0 child wrote to stderr (class loading, SELinux, app_process). */
    @Volatile var lastStderr: String = ""
        private set

    val running: Boolean get() = alive && process?.isAlive == true

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        stop()
        val info = context.applicationInfo
        val classpath = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).joinToString(":")
        val allow = listOf(context.filesDir.absolutePath, RootHelper.DIR, context.cacheDir.absolutePath)
            .joinToString(" ") { "--allow ${RootExec.quote(it)}" }
        val command = "CLASSPATH=${RootExec.quote(classpath)} exec /system/bin/app_process /system/bin " +
            "${RootBrokerMain::class.java.name} $allow"
        val started = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (error: Exception) {
            lastError = error.javaClass.simpleName
            return false
        }
        process = started
        writer = PrintWriter(started.outputStream, true)
        alive = true

        Thread({ drainErrors(started) }, "dsh-root-broker-err").apply { isDaemon = true; start() }
        Thread({ readLoop(started) }, "dsh-root-broker-out").apply { isDaemon = true; start() }
        // app_process cold start plus ART class loading can exceed 10 s on older devices.
        val ready = request("ping", JSONObject(), 20_000)
        if (ready == null) {
            val tail = lastStderr.trim().takeLast(300)
            lastError = if (tail.isEmpty()) "broker handshake failed" else "broker handshake failed: $tail"
            stop()
            return false
        }
        return true
    }

    @Synchronized
    fun stop() {
        alive = false
        runCatching { writer?.close() }
        val current = process
        process = null
        writer = null
        if (current != null) {
            // EOF ends RootBrokerMain's read loop; give the uid-0 child time to exit
            // before killing its `su` parent, otherwise app_process could be orphaned.
            runCatching {
                if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly()
            }
        }
        pending.values.forEach { it.completeExceptionally(IllegalStateException("broker stopped")) }
        pending.clear()
    }

    fun request(op: String, args: JSONObject, timeoutMs: Long = 30_000): JSONObject? {
        if (!running && !start()) return null
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val payload = JSONObject().put("id", id).put("op", op).put("args", args)
        try {
            synchronized(this) {
                val out = writer ?: return null
                out.println(payload.toString())
                out.flush()
            }
        } catch (error: Exception) {
            pending.remove(id)
            lastError = error.javaClass.simpleName
            return null
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            pending.remove(id)
            null
        }
    }

    fun exec(command: String, timeoutMs: Long = 60_000): RootExec.Result {
        val level = RootPolicy.classify(command)
        if (level == RootPolicy.Level.forbidden) {
            return RootExec.Result(126, "blocked by root policy")
        }
        val response = request(
            "exec",
            JSONObject().put("command", command).put("timeoutMs", timeoutMs.coerceIn(1_000, 120_000)),
            timeoutMs + 5_000,
        ) ?: return RootExec.Result(-1, lastError ?: "root broker unavailable")
        val value = response.optJSONObject("value")
        val result = RootExec.Result(
            value?.optInt("code", -1) ?: -1,
            value?.optString("output", "").orEmpty(),
            value?.optBoolean("timedOut") == true,
        )
        RootAudit.record(context, level, command, result.code, false)
        return result
    }

    fun appendAudit(line: String) {
        request("fs.append", JSONObject().put("path", RootAudit.PATH).put("line", line), 8_000)
    }

    fun readAudit(lines: Int): String? =
        request("fs.read", JSONObject().put("path", RootAudit.PATH).put("lines", lines), 8_000)
            ?.optJSONObject("value")?.optString("text")

    fun signalGroup(pid: Int, signal: String = "TERM"): Boolean =
        request("signal", JSONObject().put("pid", pid).put("signal", signal), 8_000)
            ?.optJSONObject("value")?.optBoolean("ok") == true

    fun clearRootData(): Boolean =
        request("cleanup", JSONObject(), 30_000)?.optJSONObject("value")?.optBoolean("ok") == true

    private fun readLoop(started: Process) {
        try {
            BufferedReader(InputStreamReader(started.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    if (json.optString("t") == "log") return@forEach
                    val id = json.optInt("id", -1)
                    pending.remove(id)?.complete(json)
                }
            }
        } catch (_: Exception) {
        } finally {
            alive = false
            pending.values.forEach { it.completeExceptionally(IllegalStateException("broker exited")) }
            pending.clear()
        }
    }

    private fun drainErrors(started: Process) {
        // Keep the tail: a class-load or SELinux failure is only visible here.
        val tail = StringBuilder()
        runCatching {
            val buffer = ByteArray(4096)
            while (true) {
                val read = started.errorStream.read(buffer)
                if (read <= 0) break
                synchronized(tail) {
                    tail.append(String(buffer, 0, read, Charsets.UTF_8))
                    if (tail.length > 4096) tail.delete(0, tail.length - 4096)
                }
            }
        }
        lastStderr = synchronized(tail) { tail.toString() }
        runCatching { File(context.cacheDir, "root-broker-stderr.log").writeText(lastStderr) }
    }

    companion object {
        @Volatile private var instance: RootBroker? = null

        fun get(context: Context): RootBroker {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: RootBroker(app).also { instance = it }
            }
        }
    }
}
