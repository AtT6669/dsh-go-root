package io.github.ccch1mneyyy.dsh_app.root

/**
 * How much damage one root command can do. Pure function: unit-testable and
 * the only place that decides whether the agent needs a confirmation.
 */
object RootPolicy {
    enum class Level { read, write, destructive, forbidden }

    private val forbidden = listOf(
        Regex("""\b(mkfs|mke2fs|make_ext4fs)\b"""),
        Regex("""\bdd\b.*\bof=/dev/(block|sd)"""),
        Regex("""\brm\s+(-[a-zA-Z]*\s+)*-?[rRfF]*[rR][fF]?\s+/(\s|$)"""),
        Regex("""\bsetenforce\s+0\b"""),
        Regex("""\bsetprop\s+ro\.secure\s+0\b"""),
        Regex("""\bpm\s+uninstall\s+(com\.topjohnwu\.magisk|me\.weishu\.kernel|bmax\.apatch)"""),
        Regex("""\bflash\b|\bfastboot\s+flash\b"""),
    )

    private val destructive = listOf(
        Regex("""\brm\s+-[a-zA-Z]*[rR]"""),
        Regex("""\bpm\s+clear\b"""),
        Regex("""\bpm\s+uninstall\b"""),
        Regex("""\breboot\b"""),
        Regex("""\bpm\s+disable\b|\bpm\s+enable\b"""),
        Regex("""\bsettings\s+put\b"""),
        Regex("""\bcmd\s+wifi\b|\bsvc\s+(data|wifi)\b"""),
        Regex("""\bchmod\s+[0-7]*777\s+/data"""),
        Regex("""\bmount\s+-o\s+rw\b"""),
    )

    private val readOnly = listOf(
        Regex("""^\s*(pm\s+list|pm\s+path|pm\s+dump)\b"""),
        Regex("""^\s*(settings\s+get|settings\s+list)\b"""),
        Regex("""^\s*(getprop|dumpsys|logcat\s+-d|cat\s+/proc)\b"""),
        Regex("""^\s*(ls|stat|find|du|df|id|whoami|uname|date)\b"""),
        Regex("""^\s*(cmd\s+package\s+list|am\s+get-current-user)\b"""),
    )

    fun classify(command: String): Level {
        val text = command.trim()
        if (text.isEmpty()) return Level.forbidden
        if (forbidden.any { it.containsMatchIn(text) }) return Level.forbidden
        if (destructive.any { it.containsMatchIn(text) }) return Level.destructive
        if (readOnly.any { it.containsMatchIn(text) }) return Level.read
        return Level.write
    }

    fun needsConfirm(level: Level): Boolean = level != Level.read
}
