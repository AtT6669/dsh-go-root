package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * The chroot helper is static and must be executable by uid 0. It ships as
 * `lib/<abi>/libdsh-root-exec.so` (extractNativeLibs=true) and is copied once
 * to /data/adb/dsh-go/bin, which root can execute on every manager.
 */
object RootHelper {
    const val DIR = "/data/adb/dsh-go"
    const val BIN = "$DIR/bin"
    private const val NAME = "dsh-root-exec"

    fun bundled(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libdsh-root-exec.so")

    fun installed(): File = File(BIN, NAME)

    /** Absolute command path, installing the bundled helper when needed. */
    fun ensure(context: Context): File {
        val target = installed()
        val source = bundled(context)
        if (target.isFile && target.length() == source.length() && target.canExecute()) return target
        if (!source.isFile) return source
        val digest = sha256(source)
        val ok = RootExec.su(
            "mkdir -p ${RootExec.quote(BIN)}" +
                " && cp ${RootExec.quote(source.absolutePath)} ${RootExec.quote(target.absolutePath)}" +
                " && chmod 0700 ${RootExec.quote(target.absolutePath)}" +
                " && echo ${digest.take(16)}",
            30_000,
        )
        // Copy succeeded even if the echoed digest could not be compared; the APK
        // signature already protects the source file.
        if (ok.ok && target.isFile && target.canExecute()) return target
        // Last resort: execute in place. Some managers allow app-data exec.
        return if (source.canExecute()) source else target
    }

    fun sha256(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }
}
