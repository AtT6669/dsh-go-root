// `dsh/root`: the App's own door to privileged execution. No command input lives in the UI;
// this channel serves the Root 增强 page (status, consent, self-test, audit) and nothing else.
package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl
import io.github.ccch1mneyyy.dsh_app.phone.background.RootExecOps
import kotlin.concurrent.thread

class RootExecPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "dsh/root").also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private val controller get() = BackgroundControl.get(context).rootExec

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "status" -> result.success(statusMap())
            "authorize" -> async(result) {
                controller.authorize()
                // One confirmation also makes the consent real: it connects now, not on first use.
                controller.ensureConnected()
                statusMap()
            }
            "revoke" -> async(result) {
                controller.revoke()
                statusMap()
            }
            "connect" -> async(result) { controller.ensureConnected(); statusMap() }
            "exec" -> {
                val command = call.argument<String>("command")
                if (command.isNullOrBlank()) {
                    result.success(failure("bad-request"))
                    return
                }
                val cwd = call.argument<String>("cwd")
                val timeout = call.argument<Int>("timeoutMs") ?: RootExecOps.DEFAULT_TIMEOUT_MS
                val env = call.argument<Map<*, *>>("env")?.entries
                    ?.mapNotNull { entry -> (entry.key as? String)?.let { it to (entry.value as? String).orEmpty() } }
                    ?.toMap().orEmpty()
                async(result) { controller.exec(command, cwd, env, timeout).map() }
            }
            "execDetach" -> {
                val command = call.argument<String>("command")
                if (command.isNullOrBlank()) {
                    result.success(failure("bad-request"))
                    return
                }
                val cwd = call.argument<String>("cwd")
                async(result) { controller.execDetach(command, cwd).map() }
            }
            "poll" -> {
                val pid = call.argument<Int>("pid")
                if (pid == null || pid <= 0) {
                    result.success(failure("bad-request"))
                    return
                }
                async(result) { controller.poll(pid).map() }
            }
            "kill" -> {
                val pid = call.argument<Int>("pid")
                if (pid == null || pid <= 0) {
                    result.success(failure("bad-request"))
                    return
                }
                async(result) { mapOf("ok" to controller.kill(pid), "pid" to pid) }
            }
            "auditTail" -> {
                val limit = (call.argument<Int>("limit") ?: 100).coerceIn(1, 500)
                async(result) { mapOf("entries" to controller.auditTail(limit)) }
            }
            "auditExport" -> async(result) { mapOf("path" to controller.auditExport()) }
            "auditClear" -> async(result) { mapOf("ok" to controller.auditClear()) }
            "grantSecureSettings" -> async(result) { BackgroundControl.get(context).grantRootSecureSettings() }
            else -> result.notImplemented()
        }
    }

    private fun statusMap(): Map<String, Any?> {
        val status = controller.status()
        return status.map() + mapOf(
            // The peer's uid, not the App's: the probe only reports connected for uid 0.
            "uid" to if (status.connected) 0 else null,
            "packageName" to context.packageName,
            "port" to RootExecServer.session()?.port,
            "sdk" to android.os.Build.VERSION.SDK_INT,
        )
    }

    private fun failure(code: String) = mapOf("ok" to false, "errorCode" to code)

    /** Every call reaches su or a process pipe: never on the platform thread. */
    private fun async(result: MethodChannel.Result, block: () -> Any?) {
        thread(name = "dsh-root-call", isDaemon = true) {
            val value = try {
                block()
            } catch (error: Exception) {
                failure(error.message ?: "executeFailed")
            }
            main.post { result.success(value) }
        }
    }
}
