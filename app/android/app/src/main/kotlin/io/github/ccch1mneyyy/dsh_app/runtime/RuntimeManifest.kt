package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RuntimeArchive(
    val asset: String,
    val bytes: Long,
    val sha256: String?,
    val uncompressedBytes: Long,
)

/**
 * Native-root launch recipe: the privileged peer execs [argv] (already expanded),
 * which chroots into the rootfs and runs the engine as real uid 0.
 */
data class RootLaunch(
    val argv: List<String>,
    val env: Map<String, String>,
    val binds: List<String>,
    val chownShared: List<String>,
    val tokenRegex: Regex,
    val logPath: String,
)

/**
 * Describes the Linux runtime bundled in this APK flavor
 * (assets/runtime/manifest.json, written by runtime/build-runtime.sh).
 */
data class RuntimeManifest(
    val runtimeVersion: String,
    val abi: String,
    /** Legacy rootfs, or base followed by engine. Upgrades only need the final archive. */
    val archives: List<RuntimeArchive>,
    val dshVersion: String,
    val nodeVersion: String,
    /** proot argv template; placeholders are expanded by [LaunchSpec]. */
    val launchArgv: List<String>,
    val launchEnv: Map<String, String>,
    /** Non-null only for bundles built with the chroot backend (schema 3). */
    val rootLaunch: RootLaunch?,
    val raw: JSONObject,
) {
    fun archivesForInstall(hasRootfs: Boolean): List<RuntimeArchive> =
        if (hasRootfs) archives.takeLast(1) else archives

    companion object {
        const val ASSET_DIR = "runtime"

        fun load(context: Context): RuntimeManifest {
            val text = context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
            return fromJson(JSONObject(text))
        }

        internal fun fromJson(json: JSONObject): RuntimeManifest {
            val schema = try { if (json.has("schema")) json.getInt("schema") else 1 }
                catch (_: Exception) { throw RuntimeUpgradeBlocked("manifest") }
            if (schema !in 1..3) throw RuntimeUpgradeBlocked("manifest")
            if (schema == 1 && json.has("layers")) throw RuntimeUpgradeBlocked("manifest")
            val archives = when {
                json.has("layers") -> try {
                    require(schema >= 2)
                    require(!json.has("rootfs") && !json.has("rootfsSha256") && !json.has("rootfsFormat"))
                    val abi = json.getString("abi")
                    require(abi in listOf("arm64-v8a", "x86_64"))
                    val layers = json.getJSONObject("layers")
                    listOf("base", "engine").map { name ->
                        val layer = layers.getJSONObject(name)
                        val digest = layer.getString("sha256")
                        val tarDigest = layer.getString("tarSha256")
                        RuntimeArchive(layer.getString("asset"), layer.getLong("bytes"),
                            digest, layer.getLong("uncompressedBytes")).also {
                            require(it.bytes > 0 && it.uncompressedBytes >= 0 &&
                                layer.getLong("tarBytes") >= 0 &&
                                it.asset in listOf("$name-$abi.tar.xz", "$name-$abi.tar.gz") &&
                                listOf(digest, tarDigest).all { hash -> hash.matches(Regex("[0-9a-f]{64}")) })
                        }
                    }
                } catch (_: Exception) {
                    throw RuntimeUpgradeBlocked("manifest")
                }
                else -> try {
                    require(!json.has("layers"))
                    listOf(RuntimeArchive(json.getString("rootfs"), json.optLong("rootfsBytes", -1L),
                        json.optString("rootfsSha256").ifEmpty { null },
                        json.optLong("uncompressedBytes", -1L)))
                } catch (_: Exception) {
                    throw RuntimeUpgradeBlocked("manifest")
                }
            }
            val launch = json.optJSONObject("launch") ?: JSONObject()
            val argvJson = launch.optJSONArray("argv")
            val argv = if (argvJson != null) List(argvJson.length()) { argvJson.getString(it) } else emptyList()
            val envJson = launch.optJSONObject("env") ?: JSONObject()
            val env = envJson.keys().asSequence().associateWith { envJson.getString(it) }
            val rootLaunch = if (schema >= 3) try {
                val root = json.getJSONObject("rootLaunch")
                val rootArgvJson = root.getJSONArray("argv")
                val rootArgv = List(rootArgvJson.length()) { rootArgvJson.getString(it) }
                val rootEnvJson = root.getJSONObject("env")
                val rootEnv = rootEnvJson.keys().asSequence().associateWith { rootEnvJson.getString(it) }
                val token = root.getJSONObject("tokenLine")
                RootLaunch(
                    argv = rootArgv,
                    env = rootEnv,
                    binds = root.optStringList("binds"),
                    chownShared = root.optStringList("chownShared"),
                    tokenRegex = Regex(token.getString("regex")),
                    logPath = token.getString("log"),
                ).also { require(it.argv.size >= 2 && it.binds.isNotEmpty()) }
            } catch (_: Exception) {
                throw RuntimeUpgradeBlocked("manifest")
            } else null
            return RuntimeManifest(
                runtimeVersion = json.getString("runtimeVersion"),
                abi = json.optString("abi"),
                archives = archives,
                dshVersion = json.optString("dshVersion"),
                nodeVersion = json.optString("nodeVersion"),
                launchArgv = argv,
                launchEnv = env,
                rootLaunch = rootLaunch,
                raw = json,
            )
        }

        private fun JSONObject.optStringList(name: String): List<String> {
            val array = optJSONArray(name) ?: return emptyList()
            return List(array.length()) { array.getString(it) }
        }
    }
}
