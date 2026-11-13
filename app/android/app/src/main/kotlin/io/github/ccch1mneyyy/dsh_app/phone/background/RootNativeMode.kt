// Native root mode: the privileged peer (already uid 0 through su) unpacks the rootfs and
// execs the chroot helper, so the guest runs as real uid 0 in a real mount namespace.
// There is no PRoot and no fake root: /proc, absolute paths, hard links and inotify are real.
//
// The wire contract lives here, next to the code that answers it (as RootExecOps does).
// Paths are confined to the app's files dir; that is a guard against mistakes, not a
// privilege boundary — the same peer already offers OP_EXEC to the authorized app.
package io.github.ccch1mneyyy.dsh_app.phone.background

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeInstaller
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeManifest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Wire contract for the native-root ops; shared by the peer and the app-side caller. */
internal object RootNativeOps {
    const val OP_CHROOT_LAUNCH = "rootChrootLaunch"
    const val OP_CHROOT_STATUS = "rootChrootStatus"
    const val OP_CHROOT_STOP = "rootChrootStop"
    const val OP_INSTALL_BEGIN = "rootInstallBegin"
    const val OP_INSTALL_STATUS = "rootInstallStatus"
    const val OP_RESET_BEGIN = "rootResetBegin"
    const val OP_CHOWN = "rootChown"
    val OPS = setOf(
        OP_CHROOT_LAUNCH, OP_CHROOT_STATUS, OP_CHROOT_STOP,
        OP_INSTALL_BEGIN, OP_INSTALL_STATUS, OP_RESET_BEGIN, OP_CHOWN,
    )

    // request keys
    const val FILES_DIR = "filesDir"
    /** The calling app's uid; the peer's package context may belong to user 0 on a shared device. */
    const val APP_UID = "appUid"
    const val LOG_PATH = "logPath"
    const val MANIFEST = "manifest"
    const val DROP_KEPT = "dropKept"
    const val ARGV = "argv"
    const val BINDS = "binds"
    const val ENV = "env"
    const val PATHS = "paths"
    const val KEEP = "keep"
    const val RECURSIVE = "recursive"
    const val PID = "pid"

    // response keys
    const val PGID = "pgid"
    const val RUNNING = "running"
    const val STOPPED = "stopped"
    const val PHASE = "phase"
    const val DONE = "done"
    const val TOTAL = "total"
    const val OUTPUT = "output"
    const val MESSAGE = "message"
    const val CHOWNED = "chowned"
    const val EXIT_CODE = RootExecOps.EXIT_CODE
    const val LOG_FILE = RootExecOps.LOG_PATH

    // phase values
    const val IDLE = "idle"
    const val INSTALLING = "installing"
    const val DONE_PHASE = "done"
    const val FAILED_PHASE = "failed"

    // reason codes
    const val ROOT_REQUIRED = "rootRequired"
    const val UNAVAILABLE = "rootUnavailable"
    const val PATH_DENIED = "rootPathDenied"
    const val BUSY = "busy"
    const val INVALID = "invalidArguments"
    const val FAILED = "rootExecuteFailed"
}

/** Only the privileged peer may construct this; [handle] answers every op above. */
internal class RootNativeCore(private val context: Context) {
    private val lock = Any()
    private val launched = HashMap<Int, Launched>()

    @Volatile private var install: Job? = null

    /** Created once: an app update brings a new APK path, and the peer is restarted with it. */
    private val app by lazy {
        runCatching { context.createPackageContext(Proto.APP_PACKAGE, 0) }.getOrNull()
    }

    fun handle(request: Bundle): Bundle = when (request.getString(Proto.OP)) {
        RootNativeOps.OP_CHROOT_LAUNCH -> launch(request)
        RootNativeOps.OP_CHROOT_STATUS -> status(request)
        RootNativeOps.OP_CHROOT_STOP -> stop(request)
        RootNativeOps.OP_INSTALL_BEGIN -> installBegin(request)
        RootNativeOps.OP_INSTALL_STATUS -> installStatus()
        RootNativeOps.OP_RESET_BEGIN -> resetBegin(request)
        RootNativeOps.OP_CHOWN -> chown(request)
        else -> failure(RootNativeOps.INVALID)
    }

    /**
     * Runs the helper in its own session (the helper calls setsid() first, so pid == pgid)
     * with stdout+stderr going to the engine log the app tails for the token line.
     */
    private fun launch(request: Bundle): Bundle {
        if (android.os.Process.myUid() != 0) return failure(RootNativeOps.ROOT_REQUIRED)
        val filesDir = filesDirOf(request) ?: return failure(RootNativeOps.PATH_DENIED)
        val rootfs = File(filesDir, RUNTIME_DIR + "/" + ROOTFS_DIR)
        if (!rootfs.isDirectory) return failure(RootNativeOps.INVALID)
        val helper = app?.applicationInfo?.nativeLibraryDir?.let { File(it, HELPER_NAME) }
        if (helper == null || !helper.isFile) return failure(RootNativeOps.UNAVAILABLE)
        val argv = request.getStringArray(RootNativeOps.ARGV)?.toList().orEmpty()
        if (argv.isEmpty() || argv.any { '\u0000' in it }) return failure(RootNativeOps.INVALID)
        val log = request.getString(RootNativeOps.LOG_PATH)?.let(::File)
            ?: return failure(RootNativeOps.INVALID)
        if (!under(log, rootfs)) return failure(RootNativeOps.PATH_DENIED)

        val command = mutableListOf(helper.absolutePath, "--root", rootfs.absolutePath)
        for (bind in request.getStringArray(RootNativeOps.BINDS).orEmpty()) {
            if (bind.isBlank() || '\u0000' in bind) return failure(RootNativeOps.INVALID)
            command += listOf("--bind", bind)
        }
        request.getBundle(RootNativeOps.ENV)?.let { env ->
            for (key in env.keySet()) {
                val value = env.getString(key) ?: continue
                if (key.isEmpty() || '=' in key || '\u0000' in key || '\u0000' in value) {
                    return failure(RootNativeOps.INVALID)
                }
                command += listOf("--env", "$key=$value")
            }
        }
        command += "--"
        command += argv

        synchronized(lock) {
            launched.entries.removeAll { !it.value.process.isAlive }
            if (launched.isNotEmpty()) return failure(RootNativeOps.BUSY)
            reapStale(rootfs)
            log.parentFile?.mkdirs()
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(log))
                    .start()
            } catch (_: Exception) {
                return failure(RootNativeOps.FAILED)
            }
            val pid = process.pidCompat()
            if (pid <= 0) {
                runCatching { process.destroyForcibly() }
                return failure(RootNativeOps.FAILED)
            }
            launched[pid] = Launched(process, rootfs)
            return Bundle().apply {
                putBoolean(Proto.OK, true)
                putInt(RootNativeOps.PID, pid)
                putInt(RootNativeOps.PGID, pid)
                putString(RootNativeOps.LOG_FILE, log.absolutePath)
            }
        }
    }

    /** Dead with a non-zero code means the helper refused; [helperFailure] names the step. */
    private fun status(request: Bundle): Bundle {
        val pid = request.getInt(RootNativeOps.PID, -1)
        val entry = synchronized(lock) { launched[pid] } ?: return failure(RootNativeOps.INVALID)
        val running = entry.process.isAlive
        return Bundle().apply {
            putBoolean(Proto.OK, true)
            putInt(RootNativeOps.PID, pid)
            putBoolean(RootNativeOps.RUNNING, running)
            if (running) return@apply
            val code = runCatching { entry.process.exitValue() }.getOrDefault(-1)
            putInt(RootNativeOps.EXIT_CODE, code)
            if (code != 0) putString(Proto.ERROR, helperFailure(code, entry.rootfs))
        }
    }

    private fun stop(request: Bundle): Bundle {
        val pid = request.getInt(RootNativeOps.PID, -1)
        val process = synchronized(lock) { launched[pid] }?.process ?: return failure(RootNativeOps.INVALID)
        var stopped = true
        if (process.isAlive) {
            // setsid() made the helper its own group leader: -pid reaches the engine and
            // anything it spawned, which a plain kill(pid) would leave running.
            runCatching { Os.kill(-pid, OsConstants.SIGTERM) }
            runCatching { Os.kill(pid, OsConstants.SIGTERM) }
            stopped = runCatching { process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!stopped) {
                runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
                runCatching { process.destroyForcibly() }
                stopped = runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
            }
        }
        synchronized(lock) { launched.remove(pid) }
        return Bundle().apply {
            putBoolean(Proto.OK, true)
            putInt(RootNativeOps.PID, pid)
            putBoolean(RootNativeOps.STOPPED, stopped)
        }
    }

    /**
     * Unpacks with the same [RuntimeInstaller] the app used to run, then hands every file to
     * the app uid so the app (and the next upgrade) can still write and delete them.
     * Progress is polled through [installStatus] rather than a file on disk.
     */
    private fun installBegin(request: Bundle): Bundle = beginJob(request) { installer, job, filesDir, uid ->
        installer.install(job.dropKept) { done, total ->
            job.done = done
            job.total = total
        }
        // Root wrote these files; the app must own them to reset, upgrade and read secrets.
        chownTree(File(filesDir, RUNTIME_DIR), uid, uid)
    }

    /** The guest writes as root, so only root can take the runtime away; [keep] waits for the next install. */
    private fun resetBegin(request: Bundle): Bundle = beginJob(request) { installer, _, _, _ ->
        installer.reset(request.getStringArray(RootNativeOps.KEEP)?.toList().orEmpty())
    }

    private fun beginJob(
        request: Bundle,
        action: (RuntimeInstaller, Job, File, Int) -> Unit,
    ): Bundle {
        if (android.os.Process.myUid() != 0) return failure(RootNativeOps.ROOT_REQUIRED)
        val filesDir = filesDirOf(request) ?: return failure(RootNativeOps.PATH_DENIED)
        val packageContext = app ?: return failure(RootNativeOps.UNAVAILABLE)
        val manifest = try {
            RuntimeManifest.fromJson(JSONObject(request.getString(RootNativeOps.MANIFEST) ?: ""))
        } catch (_: Exception) {
            return failure(RootNativeOps.INVALID)
        }
        val uid = request.getInt(RootNativeOps.APP_UID, packageContext.applicationInfo.uid)
        return synchronized(lock) {
            if (install?.phase == RootNativeOps.INSTALLING) return failure(RootNativeOps.BUSY)
            reapStale(File(filesDir, RUNTIME_DIR + "/" + ROOTFS_DIR))
            val job = Job(request.getBoolean(RootNativeOps.DROP_KEPT, false))
            install = job
            val lines = ArrayList<String>()
            val installer = RuntimeInstaller(
                context = scoped(packageContext, filesDir),
                manifest = manifest,
                onLog = { line -> synchronized(lines) { lines.add(line); if (lines.size > LOG_LINES) lines.removeAt(0) } },
            )
            thread(isDaemon = true, name = "dsh-root-install") {
                try {
                    action(installer, job, filesDir, uid)
                    job.done = job.total
                    job.phase = RootNativeOps.DONE_PHASE
                } catch (e: Throwable) {
                    job.message = e.message?.take(MESSAGE_LIMIT)
                    job.output = synchronized(lines) { lines.joinToString("\n") }
                    job.phase = RootNativeOps.FAILED_PHASE
                }
            }
            Bundle().apply { putBoolean(Proto.OK, true) }
        }
    }

    private fun installStatus(): Bundle {
        val job = install ?: return Bundle().apply {
            putBoolean(Proto.OK, true)
            putString(RootNativeOps.PHASE, RootNativeOps.IDLE)
        }
        return Bundle().apply {
            putBoolean(Proto.OK, true)
            putString(RootNativeOps.PHASE, job.phase)
            putLong(RootNativeOps.DONE, job.done)
            putLong(RootNativeOps.TOTAL, job.total)
            job.message?.let { putString(RootNativeOps.MESSAGE, it) }
            job.output?.let { putString(RootNativeOps.OUTPUT, it) }
        }
    }

    /**
     * [paths] are guest paths (`/etc`), resolved inside the rootfs; they are never host paths,
     * so a lexical check replaces canonicalisation — an absolute symlink in the tree would
     * otherwise resolve on the host and look like an escape.
     */
    private fun chown(request: Bundle): Bundle {
        if (android.os.Process.myUid() != 0) return failure(RootNativeOps.ROOT_REQUIRED)
        val filesDir = filesDirOf(request) ?: return failure(RootNativeOps.PATH_DENIED)
        val rootfs = File(filesDir, RUNTIME_DIR + "/" + ROOTFS_DIR)
        if (!rootfs.isDirectory) return failure(RootNativeOps.INVALID)
        val uid = request.getInt(RootNativeOps.APP_UID, -1)
        if (uid <= 0) return failure(RootNativeOps.INVALID)
        val targets = request.getStringArray(RootNativeOps.PATHS)?.toList().orEmpty()
        if (targets.isEmpty()) return failure(RootNativeOps.INVALID)
        val files = targets.map { guestPath(rootfs, it) ?: return failure(RootNativeOps.PATH_DENIED) }
        val recursive = request.getBoolean(RootNativeOps.RECURSIVE, true)
        var changed = 0
        for (file in files) changed += chownTree(file, uid, uid, recursive)
        return Bundle().apply {
            putBoolean(Proto.OK, true)
            putInt(RootNativeOps.CHOWNED, changed)
        }
    }

    /** `/opt/dsh-app` under [rootfs], or null when the path is not a plain absolute guest path. */
    private fun guestPath(rootfs: File, path: String): File? {
        if (!path.startsWith("/") || '\u0000' in path) return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
        return parts.fold(rootfs) { dir, name -> File(dir, name) }
    }

    /** The app's files dir as sent by the caller: absolute, canonical, and the app's own. */
    private fun filesDirOf(request: Bundle): File? {
        val raw = request.getString(RootNativeOps.FILES_DIR)?.takeIf { it.startsWith("/") } ?: return null
        val dir = try { File(raw).canonicalFile } catch (_: Exception) { return null }
        if (dir.name != "files" || !dir.path.contains(Proto.APP_PACKAGE)) return null
        return dir
    }

    /** True when [path] resolves inside [root]; symlinks cannot walk out. */
    private fun under(path: File, root: File): Boolean {
        val target = try { path.canonicalFile } catch (_: Exception) { return false }
        val base = try { root.canonicalFile } catch (_: Exception) { return false }
        return target == base || target.path.startsWith(base.path + "/")
    }

    /** The installer derives every path from filesDir, so the peer's own package context is enough. */
    private fun scoped(packageContext: Context, filesDir: File): Context =
        object : ContextWrapper(packageContext) {
            override fun getFilesDir(): File = filesDir
        }

    /** Recursive chown that never follows a symlink (a guest tree is full of absolute links). */
    private fun chownTree(file: File, uid: Int, gid: Int, recursive: Boolean = true): Int {
        val stat = try { Os.lstat(file.path) } catch (_: Exception) { return 0 }
        var count = 0
        runCatching { Os.lchown(file.path, uid, gid) }.onSuccess { count++ }
        if (recursive && OsConstants.S_ISDIR(stat.st_mode)) {
            file.listFiles()?.forEach { count += chownTree(it, uid, gid) }
        }
        return count
    }

    /** The helper writes `<rootfs>/tmp/root-init.err` before it exits; its step names the code. */
    private fun helperFailure(code: Int, rootfs: File): String {
        val detail = File(rootfs, "tmp/root-init.err")
            .takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull().orEmpty() }
        return when {
            detail.contains("step=mount") || detail.contains("step=proc") || detail.contains("step=sysfs") -> "mountFailed"
            detail.contains("step=chroot") || detail.contains("step=chdir") -> "chrootFailed"
            detail.contains("step=execv") -> "execFailed"
            code == 3 -> "mountFailed"
            code == 4 -> "chrootFailed"
            code == 5 -> "execFailed"
            else -> "engineExited"
        }
    }

    /**
     * Kills an engine a killed app left behind. It runs as root, so neither the app nor a fresh
     * peer can find it any other way; two engines on one workspace would corrupt it.
     * Only processes rooted in this rootfs whose command line names dsh are touched.
     */
    private fun reapStale(rootfs: File) {
        val target = runCatching { rootfs.canonicalPath }.getOrNull() ?: return
        val self = android.os.Process.myPid()
        val entries = File("/proc").listFiles { file -> file.name.all(Char::isDigit) } ?: return
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == self) continue
            if (runCatching { Os.readlink("/proc/$pid/root") }.getOrNull() != target) continue
            val cmdline = runCatching { File(entry, "cmdline").readText() }.getOrNull() ?: continue
            if (!cmdline.contains("dsh")) continue
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
    }

    private fun failure(code: String) = Bundle().apply {
        putBoolean(Proto.OK, false)
        putString(Proto.ERROR, code)
    }

    private class Launched(val process: Process, val rootfs: File)

    private class Job(val dropKept: Boolean) {
        @Volatile var phase: String = RootNativeOps.INSTALLING
        @Volatile var done: Long = 0
        @Volatile var total: Long = 0
        @Volatile var message: String? = null
        @Volatile var output: String? = null
    }

    private companion object {
        const val HELPER_NAME = "libdshroot-init.so"
        const val RUNTIME_DIR = "runtime"
        const val ROOTFS_DIR = "rootfs"
        const val MESSAGE_LIMIT = 400
        const val LOG_LINES = 40
    }
}
