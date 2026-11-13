package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The engine as the privileged peer runs it: the helper chroots into the unpacked rootfs and
 * execs `dsh web` as real uid 0, so no PRoot sits between the agent and the system.
 *
 * One difference from [DshProcess] shapes everything here: the app does not own the process.
 * The peer holds it, so liveness comes from a status op and stopping goes through a peer op
 * that signals the helper's process group — the app uid could do neither.
 */
class RootDshProcess(
    private val context: Context,
    private val manifest: RuntimeManifest,
    private val rootfs: File,
    private val listener: DshProcess.Listener,
) : EngineProcess {
    private val gate = RootGate(context)
    private val launch = requireNotNull(manifest.rootLaunch) { "runtime manifest has no rootLaunch" }
    private val redactor = LogRedactor()
    private val exitSignal = CountDownLatch(1)
    private val partial = StringBuilder()

    @Volatile private var handle: RootGate.Handle? = null
    @Volatile private var stopping = false
    @Volatile private var expectedExit = false
    @Volatile private var readyReported = false
    @Volatile private var failed = false

    override val launchDiagnostics = LaunchDiagnostics()
    override var port: Int = DshProcess.DEFAULT_PORT
        private set
    override var startupError: String? = null
        private set
    @Volatile override var startupTokenTimedOut: Boolean = false
        private set
    override var phoneMounted: Boolean = false
        private set
    override val direct: Boolean get() = false
    override val executionMode: String? get() = "root"

    /**
     * The manifest's argv is the guest command; [RootGate] passes it after the helper's `--`.
     * Binds and env travel beside it, so the helper sees them as its own flags.
     */
    @Synchronized
    fun start(
        guestEnv: Map<String, String>,
        binds: List<String> = emptyList(),
        safe: Boolean = false,
        phone: Boolean = false,
        searchOverlay: Boolean = false,
        authOverlay: Boolean = false,
        speechOverlay: Boolean = false,
    ) {
        check(handle == null) { "already started" }
        check(launch.argv.isNotEmpty()) { "runtime manifest has no rootLaunch.argv" }
        port = DshProcess.pickPort()
        val logPath = File(expand(launch.logPath))

        var profileSelected = false
        var searchPending = !safe && searchOverlay && SessionSearchGuest.file(rootfs).isFile
        var authPending = !safe && authOverlay && AppAuthGuest.file(rootfs).isFile
        var speechPending = !safe && speechOverlay && SpeechGuest.file(rootfs).isFile
        val mountBinds = (launch.binds + binds).map(::expand).distinct()
        val env = LinkedHashMap<String, String>()
        launch.env.forEach { (key, value) -> env[key] = expand(value) }
        guestEnv.forEach { (key, value) -> env[key] = expand(value) }
        env["DSH_GO_RUNTIME_ROOT"] = rootfs.canonicalPath

        val argv = ArrayList<String>()
        for (arg in launch.argv) {
            val selected = SafeMode.profileArg(arg, safe)
            if (selected != null) profileSelected = true
            val withPhone = if (phone && !safe && !phoneMounted) PhoneGuest.launchArg(selected ?: arg) else null
            if (withPhone != null) phoneMounted = true
            val withSearch = if (searchPending) SessionSearchGuest.launchArg(withPhone ?: selected ?: arg) else null
            if (withSearch != null) searchPending = false
            val withAuth = if (authPending) AppAuthGuest.launchArg(withSearch ?: withPhone ?: selected ?: arg) else null
            if (withAuth != null) authPending = false
            val withSpeech = if (speechPending) SpeechGuest.launchArg(withAuth ?: withSearch ?: withPhone ?: selected ?: arg) else null
            if (withSpeech != null) speechPending = false
            argv += expand(withSpeech ?: withAuth ?: withSearch ?: withPhone ?: selected ?: arg)
        }
        check(!safe || profileSelected) { "safe mode: this runtime's launch command has no `dsh web` to replace" }

        listener.onLog("[dsh-app] launch method: chroot-root (uid 0, helper process group)", "system")
        listener.onLog("[dsh-app] starting engine in ${rootfs.canonicalPath} on 127.0.0.1:$port", "system")
        if (stopping) {
            exitSignal.countDown()
            listener.onExit(this, 0, true)
            return
        }
        val started = try {
            gate.launch(argv, mountBinds, env, logPath.absolutePath)
        } catch (error: Exception) {
            failStart(error.message ?: RootGate.FAILED)
            return
        }
        handle = started
        listener.onLog("[dsh-app] engine pid ${started.pid} (process group ${started.pgid})", "system")
        thread(name = "dsh-root-wait", isDaemon = true) { watch(started) }
    }

    /** Tails the log and polls the peer in one loop: output and liveness come from different places. */
    private fun watch(started: RootGate.Handle) {
        val log = File(started.logPath)
        var offset = 0L
        var code = -1
        var lostSince = 0L
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (true) {
            offset = pump(log, offset)
            // A local stop already removed the peer's handle; polling it would only look "lost".
            if (stopping) break
            val status = gate.engineStatus(started.pid)
            if (status == null) {
                if (lostSince == 0L) lostSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - lostSince > CHANNEL_GRACE_MS) {
                    listener.onLog("[dsh-app] lost the privileged channel; engine state unknown", "system")
                    break
                }
            } else {
                lostSince = 0L
                if (!status.running) {
                    code = status.exitCode
                    if (code != 0 && status.error != null) {
                        listener.onLog("[dsh-app] engine refused: ${status.error}", "system")
                    }
                    break
                }
            }
            if (!readyReported && System.currentTimeMillis() > deadline) {
                startupTokenTimedOut = true
                break
            }
            Thread.sleep(POLL_MS)
        }
        // Drain whatever the helper wrote just before it went away.
        pump(log, offset)
        exitSignal.countDown()
        if (startupTokenTimedOut) {
            failStart("no token line after ${STARTUP_TIMEOUT_MS / 1000}s")
            return
        }
        listener.onExit(this, code, expectedExit)
    }

    @Synchronized override fun stop(expected: Boolean) {
        if (stopping) return
        stopping = true
        expectedExit = expected
        // Nothing was launched (a setup failure): no watcher will ever count the exit signal.
        val started = handle ?: run {
            exitSignal.countDown()
            return
        }
        // The peer signals the helper's whole process group: the engine and its node workers.
        runCatching { gate.stop(started.pid) }
            .onFailure { listener.onLog("[dsh-app] root stop failed: ${it.message}", "system") }
    }

    @Synchronized override fun failStart(message: String) {
        if (failed) return
        failed = true
        startupError = message
        listener.onLog("[dsh-app] start failed: $message", "system")
        listener.onStartupFailure(this)
        stop(expected = false)
    }

    /**
     * Waits for the process to be gone, not for the exit callback: the caller usually holds the
     * controller lock that the callback needs.
     */
    override fun awaitExit(millis: Long): Boolean = try {
        if (millis <= 0) {
            exitSignal.await()
            true
        } else {
            exitSignal.await(millis, TimeUnit.MILLISECONDS)
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Nothing to reap here: the engine never belonged to this uid, and the peer reaps it. */
    override fun killStrays(): Boolean = true

    private fun expand(value: String): String = vars.entries.fold(value) { text, (key, replacement) ->
        text.replace(key, replacement)
    }

    private val vars: Map<String, String>
        get() = mapOf(
            "{NATIVE_LIB_DIR}" to context.applicationInfo.nativeLibraryDir,
            "{ROOTFS}" to rootfs.canonicalPath,
            "{FILES_DIR}" to context.filesDir.absolutePath,
            "{PORT}" to port.toString(),
        )

    /** Reads what the helper appended since [offset]; returns the new offset. */
    private fun pump(log: File, offset: Long): Long {
        if (!log.isFile) return offset
        val length = log.length()
        if (length < offset) return 0
        if (length == offset) return offset
        val chunk = ByteArray((length - offset).toInt().coerceAtMost(MAX_CHUNK))
        val read = try {
            RandomAccessFile(log, "r").use { file ->
                file.seek(offset)
                file.read(chunk)
            }
        } catch (_: IOException) {
            return offset
        }
        if (read <= 0) return offset
        partial.append(String(chunk, 0, read, Charsets.UTF_8))
        while (true) {
            val end = partial.indexOf("\n")
            if (end < 0) break
            val line = partial.substring(0, end).trimEnd('\r')
            partial.delete(0, end + 1)
            if (line.isEmpty()) continue
            listener.onLog(line, "stdout", redactor.line(line))
            detectToken(line)
        }
        // A single line longer than this is a progress redraw, not something worth buffering.
        if (partial.length > MAX_LINE) partial.setLength(0)
        return offset + read
    }

    /** Gate on the manifest's line shape, then take the token and wait for the socket. */
    private fun detectToken(line: String) {
        if (readyReported || stopping) return
        if (launch.tokenRegex.find(line) == null) return
        val token = TOKEN_PARAM.find(line)?.groupValues?.get(1) ?: return
        readyReported = true
        val base = "http://127.0.0.1:$port"
        thread(name = "dsh-root-ready", isDaemon = true) {
            val deadline = System.currentTimeMillis() + SOCKET_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && !stopping) {
                if (DshProcess.canConnect(port)) {
                    listener.onReady(this@RootDshProcess, base, token)
                    return@thread
                }
                Thread.sleep(250)
            }
            if (!stopping) failStart("token seen but port $port never accepted connections")
        }
    }

    private companion object {
        /** Same shape [DshProcess] uses; the guest prints the same line under chroot. */
        val TOKEN_PARAM = Regex("""[?&#]token=([A-Za-z0-9._~%-]+)""")
        const val POLL_MS = 400L
        const val STARTUP_TIMEOUT_MS = 180_000L
        const val SOCKET_TIMEOUT_MS = 20_000L
        const val CHANNEL_GRACE_MS = 30_000L
        const val MAX_CHUNK = 1 shl 20
        const val MAX_LINE = 64 * 1024
    }
}
