// Append-only record of every privileged execution. There is no switch that turns this off:
// the audit line is the only trace of a silently authorized command.
package io.github.ccch1mneyyy.dsh_app.phone.background

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class RootAuditLog(context: Context) {
    private val dir = File(context.filesDir, "root-exec").apply { mkdirs() }
    private val file = File(dir, "root-audit.log")
    private val rotated = File(dir, "root-audit.log.1")
    private val guard = Any()

    data class Entry(val time: String, val caller: String, val exitCode: Int, val durationMs: Long, val command: String) {
        fun map(): Map<String, Any?> = mapOf(
            "time" to time, "caller" to caller, "exitCode" to exitCode,
            "durationMs" to durationMs, "command" to command,
        )
    }

    fun append(caller: String, exitCode: Int, durationMs: Long, command: String) {
        val line = buildString {
            append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            append('\t').append(if (caller == "guest") "guest" else "app")
            append('\t').append(exitCode)
            append('\t').append(durationMs)
            append('\t').append(escape(command))
            append('\n')
        }
        synchronized(guard) {
            try {
                if (file.length() + line.toByteArray(Charsets.UTF_8).size > LIMIT) {
                    rotated.delete()
                    file.renameTo(rotated)
                }
                file.appendText(line, Charsets.UTF_8)
            } catch (_: Exception) {
                // A full disk must not turn an executed command into a failed one.
            }
        }
    }

    fun tail(limit: Int): List<Entry> = synchronized(guard) {
        val count = limit.coerceIn(1, 1_000)
        val lines = try {
            if (file.isFile) file.readLines(Charsets.UTF_8) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        lines.asReversed().take(count).mapNotNull(::parse).asReversed()
    }

    /** A copy the person can hand to a file manager; the live log is never truncated by sharing. */
    fun export(): File? = synchronized(guard) {
        try {
            if (!file.isFile) return null
            val target = File(dir, "root-audit-${System.currentTimeMillis()}.log")
            file.copyTo(target, overwrite = true)
            target
        } catch (_: Exception) {
            null
        }
    }

    fun clear(): Boolean = synchronized(guard) {
        try {
            rotated.delete()
            file.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun path(): String = file.absolutePath

    private fun parse(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 5) return null
        return Entry(parts[0], parts[1], parts[2].toIntOrNull() ?: -1, parts[3].toLongOrNull() ?: 0L,
            unescape(parts.subList(4, parts.size).joinToString("\t")))
    }

    private fun escape(command: String): String {
        val clipped = if (command.length > COMMAND_LIMIT) command.take(COMMAND_LIMIT) + "…" else command
        return clipped
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    '\\' -> append('\\')
                    else -> { append(char); append(value[index + 1]) }
                }
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }

    private companion object {
        const val LIMIT = 4L * 1024 * 1024
        const val COMMAND_LIMIT = 2 * 1024
    }
}
