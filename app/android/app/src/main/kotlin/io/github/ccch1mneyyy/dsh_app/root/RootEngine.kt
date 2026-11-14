package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import io.github.ccch1mneyyy.dsh_app.runtime.PhoneStorage
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeManifest
import java.io.File

/**
 * Builds the real-root launch: `su -c "<helper> ... -- <guest argv>"`.
 *
 * The helper (see cpp/dsh-root-exec.c) creates a private mount namespace,
 * binds the Android pieces the engine needs, chroots into the rootfs and execs
 * `dsh web` as uid 0. No PRoot, no ptrace, no fake root.
 */
object RootEngine {
    const val PID_FILE = "tmp/dsh-root.pid"

    data class Plan(val command: String, val pidFile: File, val helper: File)

    fun plan(
        context: Context,
        manifest: RuntimeManifest,
        rootfs: File,
        port: Int,
        extraBinds: List<String>,
        guestEnv: Map<String, String>,
    ): Plan {
        val helper = RootHelper.ensure(context)
        val args = mutableListOf(
            helper.absolutePath,
            "--rootfs", rootfs.absolutePath,
            "--cwd", "/root",
            "--pidfile", File(rootfs, PID_FILE).absolutePath,
        )

        fun bind(host: String, guest: String) {
            args += "--bind"
            args += "$host:$guest"
        }

        // Fixed Android surface: process info, devices, platform commands and fonts.
        bind("/proc", "/proc")
        bind("/sys", "/sys")
        bind("/dev", "/dev")
        bind("/dev/pts", "/dev/pts")
        bind("/system", "/system")
        bind("/apex", "/apex")

        val seen = mutableSetOf("/proc", "/sys", "/dev", "/dev/pts", "/system", "/apex")
        for (value in extraBinds) {
            val parts = value.split(':', limit = 2)
            val host = parts.first()
            val guest = parts.getOrNull(1) ?: host
            if (guest.isBlank() || !seen.add(guest)) continue
            bind(host, guest)
        }

        args += "--tmpfs"; args += "/dev/shm:64m"
        args += "--mask"; args += "/sys/fs/selinux"

        args += "--"
        args += guestArgv(manifest, port, rootfs, guestEnv)

        return Plan(RootExec.command(args), File(rootfs, PID_FILE), helper)
    }

    private fun guestArgv(
        manifest: RuntimeManifest,
        port: Int,
        rootfs: File,
        guestEnv: Map<String, String>,
    ): List<String> {
        val template = manifest.rootLaunchArgv.ifEmpty { defaultTemplate(manifest) }
        val out = mutableListOf<String>()
        for (arg in template) {
            when (arg) {
                GUEST_ENV -> guestEnv.forEach { (key, value) -> out += "$key=$value" }
                else -> out += arg
                    .replace("{PORT}", port.toString())
                    .replace("{ROOTFS}", rootfs.absolutePath)
            }
        }
        return out
    }

    /** Used by runtimes built before `rootArgv` existed. */
    private fun defaultTemplate(manifest: RuntimeManifest): List<String> {
        val env = manifest.launchEnv
            .filterKeys { it != "PROOT_LOADER" && it != "PROOT_TMP_DIR" && it != "PROOT_L2S_DIR" && it != "LD_LIBRARY_PATH" }
            .map { (key, value) -> "$key=$value" }
        val fixed = listOf(
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
            "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
            "DSH_HOME=/root/.dsh",
            "XDG_DATA_DIRS=/usr/local/share:/usr/share:/system",
            "UV_USE_IO_URING=0", "NARB_DISABLE_NATIVE_CACHE=1",
            "NODE_OPTIONS=--dns-result-order=ipv4first",
        )
        return listOf("/usr/bin/env", "-i") + (fixed + env) + listOf(
            GUEST_ENV,
            "/bin/sh", "-c",
            "echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web" +
                " --patch /etc/dsh-app/dsh-web.patch.yml --host 127.0.0.1 --port {PORT} --no-open",
        )
    }

    /** `/sdcard` and `/storage` are the only shared-storage binds the app offers. */
    fun storageBinds(context: Context, enabled: Boolean): List<String> =
        if (!enabled) emptyList() else PhoneStorage.binds().map { value ->
            if (value.contains(':')) value else "$value:$value"
        }

    const val GUEST_ENV = "{GUEST_ENV}"
}
