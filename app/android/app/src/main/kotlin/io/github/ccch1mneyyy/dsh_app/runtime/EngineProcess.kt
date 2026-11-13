package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * What the controller needs from a running engine, whichever backend spawned it. Two
 * implementations: [DshProcess] (PRoot or the direct glibc planner, the app's own uid) and
 * [RootDshProcess] (the privileged peer's chroot, real uid 0). Only the spawn call differs.
 */
interface EngineProcess {
    val launchDiagnostics: LaunchDiagnostics
    val executionMode: String?
    val phoneMounted: Boolean
    val startupError: String?
    val port: Int
    /** The direct glibc planner's launch (never true for the root chroot backend). */
    val direct: Boolean
    val startupTokenTimedOut: Boolean

    fun stop(expected: Boolean = true)
    fun failStart(message: String)
    fun awaitExit(millis: Long = 0): Boolean
    fun killStrays(): Boolean
}
