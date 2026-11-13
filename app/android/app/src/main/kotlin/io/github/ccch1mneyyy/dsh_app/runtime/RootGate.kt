package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.os.Bundle
import io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl
import io.github.ccch1mneyyy.dsh_app.phone.background.Proto
import io.github.ccch1mneyyy.dsh_app.phone.background.RootExecController
import io.github.ccch1mneyyy.dsh_app.phone.background.RootNativeOps
import java.io.IOException

/**
 * The only door into native root mode: the peer unpacks the runtime, hands it to the app uid,
 * and later execs the chroot helper. Every call needs a live uid 0 peer — the first one pops
 * the su prompt, and until that succeeds nothing is unpacked and nothing starts.
 *
 * Failures surface as [IOException] carrying one of the reason codes below so the caller can
 * tell "the person said no" from "this ROM refused" and from "the peer vanished".
 */
class RootGate(private val context: Context) {
    data class Handle(val pid: Int, val pgid: Int, val logPath: String)
    data class EngineStatus(val running: Boolean, val exitCode: Int, val error: String?)

    private val control: RootExecController get() = BackgroundControl.get(context).rootExec

    fun authorized(): Boolean = control.isAuthorized()

    fun authorize(): Boolean = control.authorize()

    /** The gate page: store consent, then make the peer prove it is really uid 0. */
    fun request(): String? {
        authorize()
        return ready()
    }

    /** Connects (prompting su on first use) and proves the peer is really uid 0; null means ready. */
    fun ready(): String? {
        if (!authorized()) return NOT_AUTHORIZED
        val reply = control.privileged { putString(Proto.OP, Proto.OP_PROBE) } ?: return UNAVAILABLE
        if (!reply.getBoolean(Proto.OK)) return reply.getString(Proto.ERROR) ?: UNAVAILABLE
        return if (reply.getInt(Proto.UID, -1) == 0) null else REQUIRED
    }

    fun install(manifestJson: String, dropKept: Boolean) {
        reply {
            putString(Proto.OP, RootNativeOps.OP_INSTALL_BEGIN)
            putString(RootNativeOps.FILES_DIR, context.filesDir.absolutePath)
            putInt(RootNativeOps.APP_UID, android.os.Process.myUid())
            putString(RootNativeOps.MANIFEST, manifestJson)
            putBoolean(RootNativeOps.DROP_KEPT, dropKept)
        }
    }

    /** The guest writes as root, so only the peer can take the runtime away before a reinstall. */
    fun reset(manifestJson: String, keep: List<String>) {
        reply {
            putString(Proto.OP, RootNativeOps.OP_RESET_BEGIN)
            putString(RootNativeOps.FILES_DIR, context.filesDir.absolutePath)
            putInt(RootNativeOps.APP_UID, android.os.Process.myUid())
            putString(RootNativeOps.MANIFEST, manifestJson)
            putStringArray(RootNativeOps.KEEP, keep.toTypedArray())
        }
    }

    /** Polled by the installer worker while it runs; null when the peer is gone. */
    fun installStatus(): Bundle? = control.privileged {
        putString(Proto.OP, RootNativeOps.OP_INSTALL_STATUS)
    }

    fun chown(paths: List<String>, recursive: Boolean) {
        if (paths.isEmpty()) return
        reply {
            putString(Proto.OP, RootNativeOps.OP_CHOWN)
            putString(RootNativeOps.FILES_DIR, context.filesDir.absolutePath)
            putInt(RootNativeOps.APP_UID, android.os.Process.myUid())
            putStringArray(RootNativeOps.PATHS, paths.toTypedArray())
            putBoolean(RootNativeOps.RECURSIVE, recursive)
        }
    }

    fun launch(argv: List<String>, binds: List<String>, env: Map<String, String>, logPath: String): Handle {
        val reply = reply {
            putString(Proto.OP, RootNativeOps.OP_CHROOT_LAUNCH)
            putString(RootNativeOps.FILES_DIR, context.filesDir.absolutePath)
            putString(RootNativeOps.LOG_PATH, logPath)
            putStringArray(RootNativeOps.ARGV, argv.toTypedArray())
            putStringArray(RootNativeOps.BINDS, binds.toTypedArray())
            putBundle(RootNativeOps.ENV, Bundle().apply { env.forEach { (key, value) -> putString(key, value) } })
        }
        val pid = reply.getInt(RootNativeOps.PID, -1)
        if (pid <= 0) throw IOException(FAILED)
        return Handle(pid, reply.getInt(RootNativeOps.PGID, pid), reply.getString(RootNativeOps.LOG_FILE) ?: logPath)
    }

    /** Null when the channel itself is gone; the caller retries on its next tick. */
    fun engineStatus(pid: Int): EngineStatus? {
        val reply = control.privileged {
            putString(Proto.OP, RootNativeOps.OP_CHROOT_STATUS)
            putInt(RootNativeOps.PID, pid)
        } ?: return null
        // A rejected poll says nothing about the engine; only a real answer does.
        if (!reply.getBoolean(Proto.OK)) return null
        return EngineStatus(
            running = reply.getBoolean(RootNativeOps.RUNNING, false),
            exitCode = reply.getInt(RootNativeOps.EXIT_CODE, -1),
            error = reply.getString(Proto.ERROR),
        )
    }

    fun stop(pid: Int) {
        control.privileged {
            putString(Proto.OP, RootNativeOps.OP_CHROOT_STOP)
            putInt(RootNativeOps.PID, pid)
        }
    }

    private fun reply(build: Bundle.() -> Unit): Bundle {
        val reply = control.privileged(build) ?: throw IOException(UNAVAILABLE)
        if (!reply.getBoolean(Proto.OK)) throw IOException(reply.getString(Proto.ERROR) ?: FAILED)
        return reply
    }

    companion object {
        const val NOT_AUTHORIZED = "rootNotAuthorized"
        const val UNAVAILABLE = "rootUnavailable"
        const val REQUIRED = "rootRequired"
        const val FAILED = "rootExecuteFailed"
    }
}
