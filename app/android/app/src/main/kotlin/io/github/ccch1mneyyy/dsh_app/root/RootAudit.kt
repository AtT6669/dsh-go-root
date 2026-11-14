package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One line per privileged command: time, level, exit code and a redacted
 * command. Written by the broker (it owns the file); the app only reads it
 * through the broker or `su`.
 */
object RootAudit {
    const val PATH = "${RootHelper.DIR}/audit.log"
    private const val LIMIT = 1_048_576

    fun record(context: Context, level: RootPolicy.Level, command: String, code: Int, timedOut: Boolean) {
        val line = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append(" level=").append(level.name)
            append(" exit=").append(if (timedOut) "timeout" else code.toString())
            append(" cmd=").append(redact(command))
        }
        runCatching { RootBroker.get(context).appendAudit(line) }
    }

    fun tail(context: Context, lines: Int = 200): String =
        RootBroker.get(context).readAudit(lines).orEmpty()

    /** Quoted keys, long tokens and e-mail-ish strings never reach the log. */
    fun redact(command: String): String {
        var text = command
        text = Regex("""(?i)(token|key|secret|password|passwd|authorization)(=|:)\S+""").replace(text, "$1$2<redacted>")
        text = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").replace(text, "<mail>")
        text = Regex("""[A-Za-z0-9_-]{32,}""").replace(text, "<secret>")
        return if (text.length > 400) text.take(400) + "…" else text
    }

    fun rotateIfNeeded(file: File) {
        if (file.isFile && file.length() > LIMIT) {
            val rotated = File(file.parentFile, "audit.log.1")
            runCatching {
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
        }
    }
}
