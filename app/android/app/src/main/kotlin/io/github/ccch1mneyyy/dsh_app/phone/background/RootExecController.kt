// The app-side authority for privileged execution: one consent, then audited commands until revoked.
// Every entry point (App UI, guest HTTP server) ends here, so authorization and auditing have one home.
package io.github.ccch1mneyyy.dsh_app.phone.background

import android.content.Context
import android.os.Bundle
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class RootExecController(
    private val context: Context,
    private val root: RootStarter,
    private val audit: RootAuditLog = RootAuditLog(context),
) {
    data class Status(
        val authorized: Boolean,
        val connected: Boolean,
        val busy: Boolean,
        val lastErrorCode: String?,
        val auditPath: String,
    ) {
        fun map(): Map<String, Any?> = mapOf(
            "v" to 1,
            "authorized" to authorized,
            "connected" to connected,
            "busy" to busy,
            "lastErrorCode" to lastErrorCode,
            "auditPath" to auditPath,
        )
    }

    data class Result(
        val ok: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
        val durationMs: Long,
        val errorCode: String?,
    ) {
        fun map(): Map<String, Any?> = mapOf(
            "ok" to ok, "exitCode" to exitCode, "stdout" to stdout, "stderr" to stderr,
            "truncated" to truncated, "durationMs" to durationMs, "errorCode" to errorCode,
        )
    }

    data class Job(
        val pid: Int,
        val id: String?,
        val logPath: String?,
        val startedAtMs: Long,
        val running: Boolean,
        val exitCode: Int?,
        val output: String,
        val sizeBytes: Long,
        val errorCode: String?,
    ) {
        fun map(): Map<String, Any?> = mapOf(
            "pid" to pid, "id" to id, "logPath" to logPath, "startedAtMs" to startedAtMs,
            "running" to running, "exitCode" to exitCode, "output" to output,
            "sizeBytes" to sizeBytes, "errorCode" to errorCode,
        )
    }

    private val prefs = context.getSharedPreferences("root-exec", Context.MODE_PRIVATE)
    private val slots = Semaphore(2)
    private val connectLock = Any()

    @Volatile private var lastError: String? = null

    fun isAuthorized(): Boolean = prefs.getBoolean(KEY_AUTHORIZED, false)

    fun status(): Status = Status(
        authorized = isAuthorized(),
        connected = root.status().connected,
        busy = slots.availablePermits() == 0,
        lastErrorCode = lastError,
        auditPath = audit.path(),
    )

    /** A single confirmation covers every later command; the UI states that before calling this. */
    fun authorize(): Boolean {
        prefs.edit().putBoolean(KEY_AUTHORIZED, true).apply()
        lastError = null
        return true
    }

    /** Immediate: the peer goes away, and any in-flight guest call fails on its closed pipe. */
    fun revoke(): Boolean {
        prefs.edit().putBoolean(KEY_AUTHORIZED, false).apply()
        root.disconnect()
        lastError = null
        return true
    }

    /** Connects on demand; null means the transport itself is unavailable (see [RootStarter.status]). */
    fun ensureConnected(): PrivilegedChannel? {
        root.connect()?.let { return it }
        if (!isAuthorized()) return null
        synchronized(connectLock) {
            root.connect()?.let { return it }
            return root.start { isAuthorized() }
        }
    }

    fun exec(command: String, cwd: String? = null, env: Map<String, String> = emptyMap(),
             timeoutMs: Int = RootExecOps.DEFAULT_TIMEOUT_MS, caller: String = "app"): Result {
        val started = System.currentTimeMillis()
        val blocked = guard()
        if (blocked != null) return blocked
        try {
            val channel = ensureConnected()
                ?: return fail(root.status().reasonCode ?: "rootStartFailed", started, caller, command)
            val peer = send(channel, Bundle().apply {
                putString(Proto.OP, RootExecOps.OP_EXEC)
                putString(RootExecOps.COMMAND, command)
                cwd?.let { putString(RootExecOps.CWD, it) }
                if (env.isNotEmpty()) putBundle(RootExecOps.ENV, Bundle().apply {
                    env.forEach { (key, value) -> putString(key, value) }
                })
                putInt(RootExecOps.TIMEOUT_MS, timeoutMs.coerceAtMost(RootExecOps.MAX_TIMEOUT_MS))
            }) ?: return fail(lastError ?: "rootUnavailable", started, caller, command)
            if (peer.getBoolean(Proto.OK)) {
                val result = Result(
                    ok = true,
                    exitCode = peer.getInt(RootExecOps.EXIT_CODE, -1),
                    stdout = peer.getString(RootExecOps.STDOUT).orEmpty(),
                    stderr = peer.getString(RootExecOps.STDERR).orEmpty(),
                    truncated = peer.getBoolean(RootExecOps.TRUNCATED),
                    durationMs = peer.getLong(RootExecOps.DURATION_MS, System.currentTimeMillis() - started),
                    errorCode = peer.getString(Proto.ERROR),
                )
                audit.append(caller, result.exitCode, result.durationMs, command)
                return result
            }
            val code = peer.getString(Proto.ERROR).orEmpty().ifEmpty { "executeFailed" }
            return fail(code, started, caller, command)
        } finally {
            slots.release()
        }
    }

    fun execDetach(command: String, cwd: String? = null, env: Map<String, String> = emptyMap(),
                   caller: String = "app"): Job {
        val blocked = guard()
        if (blocked != null) return Job(-1, null, null, 0, false, -1, "", 0, blocked.errorCode)
        try {
            val channel = ensureConnected()
                ?: return Job(-1, null, null, 0, false, -1, "", 0, root.status().reasonCode ?: "rootStartFailed")
            val reply = send(channel, Bundle().apply {
                putString(Proto.OP, RootExecOps.OP_EXEC_DETACH)
                putString(RootExecOps.COMMAND, command)
                cwd?.let { putString(RootExecOps.CWD, it) }
                if (env.isNotEmpty()) putBundle(RootExecOps.ENV, Bundle().apply {
                    env.forEach { (key, value) -> putString(key, value) }
                })
                putString(RootExecOps.LOG_DIR, jobsDir().absolutePath)
            }) ?: return Job(-1, null, null, 0, false, -1, "", 0, lastError ?: "rootUnavailable")
            if (!reply.getBoolean(Proto.OK)) {
                val code = reply.getString(Proto.ERROR).orEmpty().ifEmpty { "executeFailed" }
                lastError = code
                return Job(-1, null, null, 0, false, -1, "", 0, code)
            }
            val pid = reply.getInt(RootExecOps.PID, -1)
            audit.append(caller, 0, 0, "[detach] $command")
            return Job(pid, reply.getString(RootExecOps.ID), reply.getString(RootExecOps.LOG_PATH),
                reply.getLong(RootExecOps.STARTED_AT_MS), true, null, "", 0, null)
        } finally {
            slots.release()
        }
    }

    fun poll(pid: Int, tailBytes: Int = RootExecOps.TAIL_LIMIT): Job {
        val channel = root.connect() ?: return Job(pid, null, null, 0, false, null, "", 0, "rootDisconnected")
        val reply = send(channel, Bundle().apply {
            putString(Proto.OP, RootExecOps.OP_EXEC_STATUS)
            putInt(RootExecOps.PID, pid)
            putInt(RootExecOps.TAIL_BYTES, tailBytes)
        }) ?: return Job(pid, null, null, 0, false, null, "", 0, lastError ?: "rootUnavailable")
        if (!reply.getBoolean(Proto.OK)) {
            return Job(pid, null, null, 0, false, null, "", 0,
                reply.getString(Proto.ERROR).orEmpty().ifEmpty { "invalidArguments" })
        }
        return Job(
            pid = reply.getInt(RootExecOps.PID, pid),
            id = reply.getString(RootExecOps.ID),
            logPath = reply.getString(RootExecOps.LOG_PATH),
            startedAtMs = reply.getLong(RootExecOps.STARTED_AT_MS),
            running = reply.getBoolean(RootExecOps.RUNNING),
            exitCode = if (reply.containsKey(RootExecOps.EXIT_CODE)) reply.getInt(RootExecOps.EXIT_CODE) else null,
            output = reply.getString(RootExecOps.OUTPUT).orEmpty(),
            sizeBytes = reply.getLong(RootExecOps.SIZE_BYTES),
            errorCode = null,
        )
    }

    fun kill(pid: Int): Boolean {
        val channel = root.connect() ?: return false
        val reply = send(channel, Bundle().apply {
            putString(Proto.OP, RootExecOps.OP_EXEC_KILL)
            putInt(RootExecOps.PID, pid)
        }) ?: return false
        if (!reply.getBoolean(Proto.OK)) return false
        audit.append("app", 0, 0, "[kill] $pid")
        return true
    }

    fun auditTail(limit: Int): List<Map<String, Any?>> = audit.tail(limit).map { it.map() }

    fun auditExport(): String? {
        val exported = audit.export() ?: return null
        val dir = File(context.filesDir, "shared").apply { mkdirs() }
        val target = File(dir, exported.name)
        return try {
            exported.copyTo(target, overwrite = true)
            exported.delete()
            target.absolutePath
        } catch (_: Exception) {
            exported.absolutePath
        }
    }

    fun auditClear(): Boolean = audit.clear()

    /** A closed pipe is an answer, not an exception: the caller reports it as a reason code. */
    private fun send(channel: PrivilegedChannel, request: Bundle): Bundle? = try {
        channel.execute(request)
    } catch (error: Exception) {
        // The channel closes itself on a failed exchange; do not touch the shared starter here.
        lastError = error.message ?: "rootUnavailable"
        null
    }

    /** Acquires one of the two execution slots, or reports why nothing may run. */
    private fun guard(): Result? {
        if (!isAuthorized()) return Result(false, -1, "", "", false, 0, "unauthorized").also { lastError = it.errorCode }
        if (!slots.tryAcquire(BUSY_WAIT_MS, TimeUnit.MILLISECONDS)) {
            return Result(false, -1, "", "", false, 0, "busy").also { lastError = it.errorCode }
        }
        return null
    }

    private fun fail(code: String, started: Long, caller: String, command: String): Result {
        val duration = System.currentTimeMillis() - started
        lastError = code
        audit.append(caller, -1, duration, command)
        return Result(false, -1, "", "", false, duration, code)
    }

    private fun jobsDir(): File = File(context.filesDir, "root-exec/jobs").apply { mkdirs() }

    private companion object {
        const val KEY_AUTHORIZED = "authorized"
        // A short wait absorbs the second concurrent caller instead of refusing it outright.
        const val BUSY_WAIT_MS = 250L
    }
}
