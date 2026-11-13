// Generic command execution over the existing root transport. No new root process lifecycle:
// these ops ride the same `su -c app_process` peer that already answers OP_PROBE.
package io.github.ccch1mneyyy.dsh_app.phone.background

import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Wire contract for the generic execution ops; shared by the peer and the app-side caller. */
internal object RootExecOps {
    const val OP_EXEC = "exec"
    const val OP_EXEC_DETACH = "execDetach"
    const val OP_EXEC_STATUS = "execStatus"
    const val OP_EXEC_KILL = "execKill"
    val OPS = setOf(OP_EXEC, OP_EXEC_DETACH, OP_EXEC_STATUS, OP_EXEC_KILL)

    const val COMMAND = "command"
    const val CWD = "cwd"
    const val ENV = "env"
    const val TIMEOUT_MS = "timeoutMs"
    const val LOG_DIR = "logDir"
    const val PID = "pid"
    const val ID = "id"
    const val TAIL_BYTES = "tailBytes"

    // One request must answer inside the transport's own 30 s budget.
    const val DEFAULT_TIMEOUT_MS = 20_000
    const val MAX_TIMEOUT_MS = 25_000
    const val MAX_COMMAND_BYTES = 8 * 1024
    const val STREAM_LIMIT = 256 * 1024
    const val TAIL_LIMIT = 64 * 1024
    const val MAX_DETACHED = 8
    const val BUSY = "busy"
    const val TIMEOUT = "timeout"
    const val INVALID = "invalidArguments"
    const val FAILED = "executeFailed"

    const val EXIT_CODE = "exitCode"
    const val STDOUT = "stdout"
    const val STDERR = "stderr"
    const val TRUNCATED = "truncated"
    const val DURATION_MS = "durationMs"
    const val LOG_PATH = "logPath"
    const val STARTED_AT_MS = "startedAtMs"
    const val OUTPUT = "output"
    const val RUNNING = "running"
    const val SIZE_BYTES = "sizeBytes"
    const val KILLED = "killed"
}

// java.lang.Process.pid() is Java 9+ and not in Android's public SDK, so read
// the private pid field of the concrete UNIXProcess via reflection. Top-level so
// RootExecCore, its nested Job class and RootNativeCore can all call it.
internal fun Process.pidCompat(): Int = try {
    val f = javaClass.getDeclaredField("pid")
    f.isAccessible = true
    f.getInt(this)
} catch (_: Throwable) {
    -1
}

/**
 * Runs on the root peer only. Every call starts from an inherited environment plus the request's
 * overrides: nothing here is a sandbox, the authorization boundary is the caller's (see
 * [RootExecController]).
 */
internal class RootExecCore {
    private val slots = Semaphore(2)
    private val lock = Any()
    private val detached = LinkedHashMap<Int, Job>()

    fun handle(request: Bundle): Bundle = when (request.getString(Proto.OP)) {
        RootExecOps.OP_EXEC -> exec(request)
        RootExecOps.OP_EXEC_DETACH -> detach(request)
        RootExecOps.OP_EXEC_STATUS -> status(request)
        RootExecOps.OP_EXEC_KILL -> kill(request)
        else -> failure(RootExecOps.INVALID)
    }

    private fun exec(request: Bundle): Bundle {
        val command = request.getString(RootExecOps.COMMAND)?.takeIf { it.isNotBlank() }
            ?: return failure(RootExecOps.INVALID)
        if (command.toByteArray(Charsets.UTF_8).size > RootExecOps.MAX_COMMAND_BYTES) return failure(RootExecOps.INVALID)
        val timeout = timeoutOf(request)
        if (!slots.tryAcquire()) return failure(RootExecOps.BUSY)
        val started = System.currentTimeMillis()
        try {
            val builder = processBuilder(command, request) ?: return failure(RootExecOps.INVALID)
            val child = try {
                builder.start()
            } catch (_: Exception) {
                return failure(RootExecOps.FAILED)
            }
            try {
                val out = Capture()
                val err = Capture()
                val readers = listOf(
                    thread(isDaemon = true, name = "dsh-root-exec-out") { out.pump(child.inputStream) },
                    thread(isDaemon = true, name = "dsh-root-exec-err") { err.pump(child.errorStream) },
                )
                val finished = try {
                    child.waitFor(timeout.toLong(), TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!finished) {
                    runCatching { child.destroyForcibly() }
                    runCatching { child.waitFor(2, TimeUnit.SECONDS) }
                }
                readers.forEach { runCatching { it.join(1_000) } }
                val code = if (finished) runCatching { child.exitValue() }.getOrDefault(-1) else -1
                val reply = Bundle().apply {
                    putBoolean(Proto.OK, true)
                    putInt(RootExecOps.EXIT_CODE, code)
                    putString(RootExecOps.STDOUT, out.text())
                    putString(RootExecOps.STDERR, err.text())
                    putBoolean(RootExecOps.TRUNCATED, out.truncated || err.truncated)
                    putLong(RootExecOps.DURATION_MS, System.currentTimeMillis() - started)
                }
                if (!finished) reply.putString(Proto.ERROR, RootExecOps.TIMEOUT)
                return reply
            } finally {
                // Readers may still block on a dead peer's pipe; closing it releases them.
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
            }
        } finally {
            slots.release()
        }
    }

    private fun detach(request: Bundle): Bundle {
        val command = request.getString(RootExecOps.COMMAND)?.takeIf { it.isNotBlank() }
            ?: return failure(RootExecOps.INVALID)
        if (command.toByteArray(Charsets.UTF_8).size > RootExecOps.MAX_COMMAND_BYTES) return failure(RootExecOps.INVALID)
        val dir = request.getString(RootExecOps.LOG_DIR)?.let(::File)?.takeIf { it.isAbsolute }
            ?: return failure(RootExecOps.INVALID)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return failure(RootExecOps.INVALID)
        val builder = processBuilder(command, request) ?: return failure(RootExecOps.INVALID)
        val log = File(dir, "${UUID.randomUUID()}.log")
        val process = try {
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(log)).start()
        } catch (_: Exception) {
            return failure(RootExecOps.FAILED)
        }
        val job = Job(process, log, System.currentTimeMillis())
        val accepted = synchronized(lock) {
            pruneLocked()
            val running = detached.values.count { it.exit == null }
            if (running >= RootExecOps.MAX_DETACHED) false else { detached[process.pidCompat()] = job; true }
        }
        if (!accepted) {
            runCatching { process.destroyForcibly() }
            return failure(RootExecOps.BUSY)
        }
        thread(isDaemon = true, name = "dsh-root-exec-job") {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            synchronized(lock) { job.exit = code; job.endedAtMs = System.currentTimeMillis() }
        }
        return Bundle().apply {
            putBoolean(Proto.OK, true)
            putInt(RootExecOps.PID, process.pidCompat())
            putString(RootExecOps.ID, log.nameWithoutExtension)
            putString(RootExecOps.LOG_PATH, log.absolutePath)
            putLong(RootExecOps.STARTED_AT_MS, job.startedAtMs)
        }
    }

    private fun status(request: Bundle): Bundle {
        val job = synchronized(lock) { lookupLocked(request) } ?: return failure(RootExecOps.INVALID)
        val limit = request.getInt(RootExecOps.TAIL_BYTES, RootExecOps.TAIL_LIMIT)
            .coerceIn(1_024, RootExecOps.TAIL_LIMIT)
        return Bundle().apply {
            putBoolean(Proto.OK, true)
            putInt(RootExecOps.PID, job.pid)
            putString(RootExecOps.ID, job.log.nameWithoutExtension)
            putString(RootExecOps.LOG_PATH, job.log.absolutePath)
            putLong(RootExecOps.STARTED_AT_MS, job.startedAtMs)
            putBoolean(RootExecOps.RUNNING, job.exit == null)
            putLong(RootExecOps.SIZE_BYTES, job.log.length())
            putString(RootExecOps.OUTPUT, tail(job.log, limit))
            job.exit?.let { putInt(RootExecOps.EXIT_CODE, it) }
            if (job.exit != null) putLong(RootExecOps.DURATION_MS, job.endedAtMs - job.startedAtMs)
        }
    }

    private fun kill(request: Bundle): Bundle {
        val job = synchronized(lock) { lookupLocked(request) } ?: return failure(RootExecOps.INVALID)
        if (job.exit != null) return Bundle().apply {
            putBoolean(Proto.OK, true); putInt(RootExecOps.PID, job.pid); putBoolean(RootExecOps.KILLED, false)
            putInt(RootExecOps.EXIT_CODE, job.exit ?: -1)
        }
        runCatching { Os.kill(job.pid, OsConstants.SIGTERM) }
        runCatching { job.process.destroy() }
        val ended = runCatching { job.process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!ended) {
            runCatching { Os.kill(job.pid, OsConstants.SIGKILL) }
            runCatching { job.process.destroyForcibly() }
        }
        return Bundle().apply {
            putBoolean(Proto.OK, true)
            putInt(RootExecOps.PID, job.pid)
            putBoolean(RootExecOps.KILLED, true)
            putString(RootExecOps.LOG_PATH, job.log.absolutePath)
        }
    }

    /** Absolute paths and a plain `sh -c`: the command is the caller's own choice, not untrusted input. */
    private fun processBuilder(command: String, request: Bundle): ProcessBuilder? {
        val builder = ProcessBuilder("/system/bin/sh", "-c", command)
        request.getString(RootExecOps.CWD)?.let { cwd ->
            val dir = File(cwd)
            if (!dir.isDirectory) return null
            builder.directory(dir)
        }
        val env = builder.environment()
        request.getBundle(RootExecOps.ENV)?.let { extra ->
            for (key in extra.keySet()) {
                val value = extra.getString(key) ?: continue
                if (key.isEmpty() || key.contains('=') || key.contains('\u0000') || value.contains('\u0000')) continue
                env[key] = value
            }
        }
        if (env["PATH"] == null) env["PATH"] = "/system/bin:/system/xbin"
        return builder
    }

    private fun timeoutOf(request: Bundle): Int {
        val raw = request.getInt(RootExecOps.TIMEOUT_MS, RootExecOps.DEFAULT_TIMEOUT_MS)
        return if (raw <= 0) RootExecOps.DEFAULT_TIMEOUT_MS else raw.coerceAtMost(RootExecOps.MAX_TIMEOUT_MS)
    }

    private fun lookupLocked(request: Bundle): Job? = when {
        request.containsKey(RootExecOps.PID) -> detached[request.getInt(RootExecOps.PID, -1)]
        else -> request.getString(RootExecOps.ID)?.let { id ->
            detached.values.firstOrNull { it.log.nameWithoutExtension == id }
        }
    }

    /** Finished jobs keep their log on disk; only the in-memory window is bounded. */
    private fun pruneLocked() {
        if (detached.size < RootExecOps.MAX_DETACHED) return
        val finished = detached.entries.filter { it.value.exit != null }.sortedBy { it.value.endedAtMs }
        finished.take(detached.size - RootExecOps.MAX_DETACHED + 1).forEach { detached.remove(it.key) }
    }

    private fun tail(file: File, limit: Int): String = try {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val from = (size - limit).coerceAtLeast(0)
            raf.seek(from)
            val bytes = ByteArray((size - from).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    } catch (_: Exception) {
        ""
    }

    private fun failure(code: String) = Bundle().apply {
        putBoolean(Proto.OK, false)
        putString(Proto.ERROR, code)
    }

    private class Job(
        val process: Process,
        val log: File,
        val startedAtMs: Long,
        @Volatile var exit: Int? = null,
        @Volatile var endedAtMs: Long = 0,
    ) {
        val pid: Int get() = process.pidCompat()
    }

    /** Keeps the first [RootExecOps.STREAM_LIMIT] bytes and drains the rest so the peer never blocks. */
    private class Capture {
        private val buffer = java.io.ByteArrayOutputStream()
        @Volatile var truncated = false
        private val guard = Any()

        fun pump(stream: InputStream) {
            val chunk = ByteArray(8 * 1024)
            try {
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    synchronized(guard) {
                        val room = RootExecOps.STREAM_LIMIT - buffer.size()
                        if (room > 0) buffer.write(chunk, 0, minOf(room, read))
                        if (read > room) truncated = true
                    }
                }
            } catch (_: Exception) {
                // A killed peer closes its pipe; what was read stays.
            }
        }

        fun text(): String = synchronized(guard) { buffer.toString("UTF-8") }
    }
}
