// Loopback HTTP bridge so the PRoot guest (engine, plugins, the dsh-root CLI) can reach the root
// peer. PRoot does not isolate the network namespace, so 127.0.0.1 here is the phone's loopback.
// The token never touches disk: it is handed to the engine in its own environment.
package io.github.ccch1mneyyy.dsh_app.root

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl
import io.github.ccch1mneyyy.dsh_app.phone.background.RootExecController
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// Plenty for two payloads plus the JSON envelope; anything larger is refused rather than read.
private const val MAX_HEADER = 16 * 1024
private const val MAX_BODY = 64 * 1024

object RootExecServer {
    data class Session(val port: Int, val token: String) {
        fun guestEnv(): Map<String, String> = mapOf(
            "DSH_ROOT_EXEC_URL" to "http://127.0.0.1:$port",
            "DSH_ROOT_EXEC_TOKEN" to token,
            "DSH_ROOT_EXEC_VERSION" to "1",
        )
    }

    private class Running(
        val socket: ServerSocket,
        val token: String,
        val controller: RootExecController,
        val connectivity: ConnectivityManager?,
    )

    @Volatile private var running: Running? = null
    private val guard = Any()

    fun session(): Session? = running?.let { Session(it.socket.localPort, it.token) }

    /** Idempotent: one listener per app process, kept across engine restarts. */
    fun ensureStarted(context: Context): Session? = synchronized(guard) {
        running?.let { return Session(it.socket.localPort, it.token) }
        val controller = BackgroundControl.get(context).rootExec
        val socket = try {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            return null
        }
        val current = Running(
            socket, token(), controller,
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager,
        )
        running = current
        thread(isDaemon = true, name = "dsh-root-exec-server") { accept(current) }
        Session(socket.localPort, current.token)
    }

    fun stop() {
        val current = synchronized(guard) { running.also { running = null } } ?: return
        runCatching { current.socket.close() }
    }

    private fun accept(current: Running) {
        val workers = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "dsh-root-exec-http").apply { isDaemon = true }
        }
        try {
            while (!current.socket.isClosed) {
                val socket = current.socket.accept()
                workers.execute { handle(current, socket) }
            }
        } catch (_: Exception) {
            // Closed by stop() or by process exit.
        } finally {
            workers.shutdownNow()
            runCatching { current.socket.close() }
        }
    }

    private fun handle(current: Running, socket: Socket) {
        socket.use {
            runCatching {
                it.soTimeout = 30_000
                val request = try {
                    Request.read(it.getInputStream())
                } catch (_: Exception) {
                    respond(it.getOutputStream(), 413, error("payload-too-large"))
                    return@runCatching
                } ?: return@runCatching
                val deny = authorize(current, it, request)
                if (deny != null) {
                    respond(it.getOutputStream(), 403, error(deny))
                    return@runCatching
                }
                val reply = route(current.controller, request)
                respond(it.getOutputStream(), reply.first, reply.second)
            }
        }
    }

    /** Token first: it is the actual secret. The owner uid is the second lock (API 29+). */
    private fun authorize(current: Running, socket: Socket, request: Request): String? {
        val header = request.headers["authorization"].orEmpty()
        if (!header.startsWith("Bearer ") || !constantEquals(header.removePrefix("Bearer "), current.token)) {
            return "unauthorized"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val owner = ownerUid(current.connectivity, socket)
            if (owner != null && owner != Process.myUid()) return "forbidden"
        }
        return null
    }

    private fun ownerUid(manager: ConnectivityManager?, socket: Socket): Int? = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val local = socket.localSocketAddress as? InetSocketAddress ?: return null
        val remote = socket.remoteSocketAddress as? InetSocketAddress ?: return null
        manager?.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, local, remote)
            ?.takeIf { it != Process.INVALID_UID }
    } catch (_: Exception) {
        null
    }

    private fun route(controller: RootExecController, request: Request): Pair<Int, JSONObject> {
        val authorized = controller.isAuthorized()
        return when {
            request.method == "GET" && request.path == "/v1/ping" -> 200 to JSONObject()
                .put("ok", true).put("v", 1).put("authorized", authorized)
            !authorized -> 403 to error("unauthorized")
            request.method == "POST" && request.path == "/v1/exec" -> exec(controller, request)
            request.method == "GET" && request.path == "/v1/status" -> status(controller, request)
            request.method == "POST" && request.path == "/v1/kill" -> kill(controller, request)
            else -> 404 to error("notFound")
        }
    }

    private fun exec(controller: RootExecController, request: Request): Pair<Int, JSONObject> {
        val body = try {
            JSONObject(request.body)
        } catch (_: Exception) {
            return 400 to error("bad-request")
        }
        val command = body.optString("command").takeIf { it.isNotBlank() } ?: return 400 to error("bad-request")
        val cwd = body.optString("cwd").takeIf { it.isNotBlank() }
        val timeout = body.optInt("timeoutMs", 0).takeIf { it > 0 } ?: 20_000
        val env = LinkedHashMap<String, String>()
        body.optJSONObject("env")?.let { map ->
            map.keys().forEach { key -> env[key] = map.optString(key) }
        }
        if (body.optBoolean("detach")) {
            val job = controller.execDetach(command, cwd, env, caller = "guest")
            return if (job.pid > 0) 200 to JSONObject()
                .put("ok", true).put("pid", job.pid).put("id", job.id).put("logPath", job.logPath)
                .put("startedAtMs", job.startedAtMs)
            else 503 to error(job.errorCode ?: "executeFailed")
        }
        val result = controller.exec(command, cwd, env, timeout, caller = "guest")
        if (!result.ok) {
            return statusFor(result.errorCode) to error(result.errorCode ?: "executeFailed")
        }
        return 200 to JSONObject()
            .put("ok", true).put("exitCode", result.exitCode).put("stdout", result.stdout)
            .put("stderr", result.stderr).put("truncated", result.truncated).put("durationMs", result.durationMs)
    }

    private fun status(controller: RootExecController, request: Request): Pair<Int, JSONObject> {
        val pid = request.query["pid"]?.toIntOrNull() ?: return 400 to error("bad-request")
        val job = controller.poll(pid)
        if (job.errorCode != null) return statusFor(job.errorCode) to error(job.errorCode)
        return 200 to JSONObject()
            .put("ok", true).put("pid", job.pid).put("running", job.running)
            .put("exitCode", job.exitCode ?: JSONObject.NULL).put("output", job.output)
            .put("sizeBytes", job.sizeBytes).put("logPath", job.logPath)
    }

    private fun kill(controller: RootExecController, request: Request): Pair<Int, JSONObject> {
        val pid = try {
            JSONObject(request.body).optInt("pid", -1)
        } catch (_: Exception) {
            -1
        }
        if (pid <= 0) return 400 to error("bad-request")
        return if (controller.kill(pid)) 200 to JSONObject().put("ok", true).put("pid", pid)
        else 404 to error("notRunning")
    }

    private fun respond(output: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        else -> "Service Unavailable"
    }

    private fun error(code: String) = JSONObject().put("ok", false).put("error", code)

    /** Transport or consent gaps are 503; a rejected call is 409. */
    private fun statusFor(code: String?): Int = when (code) {
        "unauthorized" -> 403
        "rootUnavailable", "rootStartFailed", "rootDisconnected", "rootTimeout" -> 503
        else -> 409
    }

    private fun token(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var diff = 0
        for (index in left.indices) diff = diff or (left[index].code xor right[index].code)
        return diff == 0
    }

    /** One request per connection; the CLI sends `Connection: close`. */
    internal class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    ) {
        companion object {
            fun read(input: InputStream): Request? {
                val head = readHead(input) ?: return null
                val lines = head.split("\r\n")
                val requestLine = lines.firstOrNull()?.split(' ') ?: return null
                if (requestLine.size < 2) return null
                val target = requestLine[1]
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "").split('&')
                    .filter { it.isNotBlank() }
                    .associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val headers = lines.drop(1).mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
                }.toMap()
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length > MAX_BODY) throw IllegalStateException("payload too large")
                val body = if (length > 0) String(readFully(input, length), Charsets.UTF_8) else ""
                return Request(requestLine[0].uppercase(), path, query, headers, body)
            }

            private fun readHead(input: InputStream): String? {
                val buffer = StringBuilder()
                while (true) {
                    val value = input.read()
                    if (value < 0) return if (buffer.isEmpty()) null else buffer.toString()
                    buffer.append(value.toChar())
                    if (buffer.length > MAX_HEADER) throw IllegalStateException("header too large")
                    if (buffer.endsWith("\r\n\r\n")) return buffer.removeSuffix("\r\n\r\n").toString()
                }
            }

            private fun readFully(input: InputStream, length: Int): ByteArray {
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(bytes, offset, length - offset)
                    if (read < 0) break
                    offset += read
                }
                return if (offset == length) bytes else bytes.copyOf(offset)
            }
        }
    }
}
