package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import java.io.File

/** Where the root gate stands. Persisted only as a hint; every cold start re-probes. */
enum class RootPhase {
    unknown, checking, granted, denied, noManager, timeout, revoked, unsupported;

    val blocking: Boolean get() = this != granted

    companion object {
        fun parse(value: String?): RootPhase = entries.firstOrNull { it.name == value } ?: unknown
    }
}

data class RootStatus(
    val phase: RootPhase = RootPhase.unknown,
    val detail: String? = null,
    val manager: String? = null,
    val uid: Int? = null,
    val checkedAt: Long = 0,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "phase" to phase.name,
        "detail" to detail,
        "manager" to manager,
        "uid" to uid,
        "checkedAt" to checkedAt,
    )
}

/**
 * `su -c "id -u"` is the only admission test. A manager being installed is a
 * hint, never permission to continue: a manager can be present while the grant
 * was refused or revoked.
 */
object RootProbe {
    const val PREFS = "dsh_root"
    private const val KEY_PHASE = "phase"
    private const val KEY_DETAIL = "detail"
    private const val KEY_MANAGER = "manager"
    private const val KEY_UID = "uid"
    private const val KEY_AT = "at"
    private const val TIMEOUT_MS = 30_000L

    fun cached(context: Context): RootStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RootStatus(
            phase = RootPhase.parse(prefs.getString(KEY_PHASE, null)),
            detail = prefs.getString(KEY_DETAIL, null),
            manager = prefs.getString(KEY_MANAGER, null),
            uid = prefs.getInt(KEY_UID, -1).takeIf { it >= 0 },
            checkedAt = prefs.getLong(KEY_AT, 0),
        )
    }

    fun manager(): String? = when {
        File("/data/adb/magisk").exists() || File("/data/adb/magisk.img").exists() -> "magisk"
        File("/data/adb/ksu").exists() -> "kernelsu"
        File("/data/adb/ap").exists() || File("/data/adb/apatch").exists() -> "apatch"
        File("/system/bin/su").exists() || File("/system/xbin/su").exists() -> "su"
        else -> null
    }

    /** Runs the grant prompt. Called only from an explicit gate action, never from build/status. */
    fun request(context: Context): RootStatus {
        val result = RootExec.su("id -u", TIMEOUT_MS)
        val status = when {
            result.timedOut -> RootStatus(RootPhase.timeout, "等待 Root 授权超时", manager(), null, now())
            result.code == 127 -> RootStatus(RootPhase.noManager, "未找到 su", manager(), null, now())
            result.ok && result.output.lineSequence().any { it.trim() == "0" } ->
                RootStatus(RootPhase.granted, null, manager(), 0, now())
            result.ok -> RootStatus(RootPhase.denied, firstLine(result.output), manager(), null, now())
            else -> RootStatus(RootPhase.denied, firstLine(result.output), manager(), null, now())
        }
        save(context, status)
        return status
    }

    fun save(context: Context, status: RootStatus) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHASE, status.phase.name)
            .putString(KEY_DETAIL, status.detail)
            .putString(KEY_MANAGER, status.manager)
            .putInt(KEY_UID, status.uid ?: -1)
            .putLong(KEY_AT, status.checkedAt)
            .apply()
    }

    /** Marks a live grant as lost (broker died / engine start refused). */
    fun revoked(context: Context, detail: String): RootStatus {
        val status = RootStatus(RootPhase.revoked, detail, manager(), null, now())
        save(context, status)
        return status
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.take(200)

    private fun now(): Long = System.currentTimeMillis()
}
