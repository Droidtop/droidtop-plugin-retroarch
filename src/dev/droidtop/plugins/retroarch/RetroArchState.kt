package dev.droidtop.plugins.retroarch

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** The two switches on the plugin's panel and settings page. */
internal data class RetroArchSettings(
    /** Download a system's core when RetroArch is chosen for that system and the core is not downloaded yet. */
    val autoDownload: Boolean = true,
    /** Put a core that was downloaded that way into RetroArch's own cores folder, when an elevated helper is allowed. */
    val autoInstall: Boolean = true,
)

/** A system droidtop told this plugin RetroArch is the player for, with the core RetroArch would load for it. */
internal data class SystemCore(val id: String, val name: String, val core: String, val playerPackage: String)

/** What RetroArch's own cores folder held the last time the user asked to look (through the elevated helper). */
internal data class CoreScan(val pkg: String, val cores: Set<String>)

/**
 * The plugin's small files, all in its private data directory (nothing leaves the device): its settings, the systems
 * droidtop has said RetroArch plays, and the last look into RetroArch's cores folder. When the user has allowed "See
 * your library" the panel lists the systems droidtop itself reports ([LibrarySystems]); the systems kept here are the
 * fallback for when it has not: the ones heard about from `library.default_player_changed`, which fires when a player
 * is chosen for a system.
 */
internal class RetroArchState(private val dir: File) {
    @Synchronized
    fun settings(): RetroArchSettings {
        val json = read(SETTINGS) ?: return RetroArchSettings()
        return RetroArchSettings(json.optBoolean("auto_download", true), json.optBoolean("auto_install", true))
    }

    @Synchronized
    fun saveSettings(settings: RetroArchSettings) {
        write(SETTINGS, JSONObject().put("auto_download", settings.autoDownload).put("auto_install", settings.autoInstall))
    }

    @Synchronized
    fun systems(): List<SystemCore> {
        val json = read(SYSTEMS) ?: return emptyList()
        return json.keys().asSequence().mapNotNull { id ->
            val entry = json.optJSONObject(id) ?: return@mapNotNull null
            SystemCore(id, entry.optString("name").ifBlank { id }, entry.optString("core"), entry.optString("package"))
        }.sortedBy { it.name.lowercase() }.toList()
    }

    @Synchronized
    fun rememberSystem(system: SystemCore) {
        val json = read(SYSTEMS) ?: JSONObject()
        json.put(system.id, JSONObject().put("name", system.name).put("core", system.core).put("package", system.playerPackage))
        write(SYSTEMS, json)
    }

    @Synchronized
    fun forgetSystem(id: String) {
        val json = read(SYSTEMS) ?: return
        if (json.has(id)) {
            json.remove(id)
            write(SYSTEMS, json)
        }
    }

    @Synchronized
    fun scan(): CoreScan? {
        val json = read(SCAN) ?: return null
        val cores = json.optJSONArray("cores") ?: JSONArray()
        return CoreScan(json.optString("package"), buildSet { for (i in 0 until cores.length()) add(cores.optString(i)) })
    }

    @Synchronized
    fun saveScan(scan: CoreScan) {
        write(SCAN, JSONObject().put("package", scan.pkg).put("cores", JSONArray(scan.cores.sorted())))
    }

    private fun read(name: String): JSONObject? = runCatching { JSONObject(File(dir, name).readText()) }.getOrNull()

    private fun write(name: String, json: JSONObject) {
        dir.mkdirs()
        val target = File(dir, name)
        val staged = File(dir, "$name.tmp")
        staged.writeText(json.toString())
        if (!staged.renameTo(target)) {
            target.writeText(json.toString())
            staged.delete()
        }
    }

    private companion object {
        const val SETTINGS = "settings.json"
        const val SYSTEMS = "systems.json"
        const val SCAN = "retroarch_cores.json"
    }
}

/**
 * droidtop's answer to `library.read` `systems` (docs/plugin-api.md A1): every system the user has games for with the
 * emulator a launch would use. Only the systems RetroArch plays matter here, read the same way the event is: by the
 * player's package.
 */
internal object LibrarySystems {
    /**
     * The RetroArch systems in [data], or null when the answer is not a complete list (droidtop said `ready: false`, or
     * sent no `systems`), so the caller keeps the event-fed list instead of showing an empty one.
     */
    fun retroArchSystems(data: JSONObject?, retroArchPackages: Collection<String>): List<SystemCore>? {
        if (data == null || !data.optBoolean("ready", false)) return null
        val rows = data.optJSONArray("systems") ?: return null
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("id")
                val playerPackage = row.optString("playerPackage")
                if (id.isBlank() || playerPackage !in retroArchPackages) continue
                add(SystemCore(id, row.optString("name").ifBlank { id }, row.optString("core"), playerPackage))
            }
        }.sortedBy { it.name.lowercase() }
    }
}

/** Where one core stands for the user, in the order the panel prefers to say it. */
internal enum class CoreStatus { IN_RETROARCH, DOWNLOADED, MISSING, NO_CORE }

internal object RetroArchCores {
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    /** A core's short name ("snes9x"), the only thing that goes into a download address, a file name and a helper command. */
    fun isValidName(core: String): Boolean = NAME.matches(core)

    fun fileName(core: String): String = core + "_libretro_android.so"

    fun nameOf(fileName: String): String? = fileName.takeIf { it.endsWith("_libretro_android.so") }?.removeSuffix("_libretro_android.so")?.takeIf { it.isNotEmpty() }

    /** [inRetroArch] is null until the user has looked into RetroArch's folder through the elevated helper. */
    fun status(core: String, inRetroArch: Set<String>?, downloaded: Set<String>): CoreStatus = when {
        core.isBlank() -> CoreStatus.NO_CORE
        inRetroArch != null && core in inRetroArch -> CoreStatus.IN_RETROARCH
        core in downloaded -> CoreStatus.DOWNLOADED
        else -> CoreStatus.MISSING
    }

    fun label(status: CoreStatus, looked: Boolean): String = when (status) {
        CoreStatus.IN_RETROARCH -> "In RetroArch"
        CoreStatus.DOWNLOADED -> if (looked) "Downloaded, not in RetroArch" else "Downloaded"
        CoreStatus.MISSING -> if (looked) "Not in RetroArch" else "Not downloaded"
        CoreStatus.NO_CORE -> "No core set"
    }
}
