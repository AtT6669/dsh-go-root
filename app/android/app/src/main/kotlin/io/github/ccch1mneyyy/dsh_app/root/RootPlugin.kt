package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * `dsh/root`: the Dart side of the root gate and of the agent's host-level
 * commands. `status` is never allowed to run `su`; only `request` (an explicit
 * user action) may prompt the manager.
 */
class RootPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dsh-root-plugin").apply { isDaemon = true }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "dsh/root")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        io.shutdownNow()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "status" -> result.success(RootProbe.cached(context).toMap())
            "request" -> io.execute {
                val status = RootProbe.request(context)
                if (status.phase == RootPhase.granted && !RootBroker.get(context).start()) {
                    val revoked = RootProbe.revoked(context, "RootBroker 启动失败")
                    post { result.success(revoked.toMap()) }
                } else {
                    post { result.success(status.toMap()) }
                }
            }
            "brokerStart" -> io.execute {
                val ok = RootBroker.get(context).start()
                post { result.success(mapOf("ok" to ok, "error" to RootBroker.get(context).lastError)) }
            }
            "brokerStop" -> {
                RootBroker.get(context).stop()
                result.success(true)
            }
            "exec" -> io.execute {
                val command = call.argument<String>("command").orEmpty()
                val timeout = call.argument<Int>("timeoutMs") ?: 60_000
                val purpose = call.argument<String>("purpose")
                val level = RootPolicy.classify(command)
                val exec = RootBroker.get(context).exec(command, timeout.toLong())
                RootAudit.record(context, level, "purpose=$purpose cmd=$command", exec.code, exec.timedOut)
                post {
                    result.success(
                        mapOf(
                            "code" to exec.code,
                            "output" to exec.output.take(64 * 1024),
                            "level" to level.name,
                            "timedOut" to exec.timedOut,
                        ),
                    )
                }
            }
            "audit" -> io.execute {
                val text = RootAudit.tail(context, call.argument<Int>("lines") ?: 200)
                post { result.success(text) }
            }
            "clearData" -> io.execute {
                val ok = RootBroker.get(context).clearRootData()
                post { result.success(ok) }
            }
            "helper" -> result.success(RootHelper.ensure(context).absolutePath)
            else -> result.notImplemented()
        }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
