package dev.droidtop.plugins.retroarch

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

/** The plugin's own small text files by name: droidtop's data API (the plugin runs contained and opens no path). */
internal interface PluginFiles {
    fun read(name: String): String?
    fun write(name: String, text: String)
}

/**
 * The plugin's small files, all in its own data kept by droidtop (nothing leaves the device): its settings, the systems
 * droidtop has said RetroArch plays, and the last look into RetroArch's cores folder. When the user has allowed "See
 * your library" the panel lists the systems droidtop itself reports ([LibrarySystems]); the systems kept here are the
 * fallback for when it has not: the ones heard about from `library.default_player_changed`, which fires when a player
 * is chosen for a system.
 */
internal class RetroArchState(private val files: PluginFiles) {
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

    private fun read(name: String): JSONObject? = runCatching { files.read(name)?.let { JSONObject(it) } }.getOrNull()

    // droidtop writes each file whole through a temporary file, so a crash never leaves half of one.
    private fun write(name: String, json: JSONObject) = files.write(name, json.toString())

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

    /**
     * The ABI of the cores a RetroArch build loads. droidtop's plugin API gives a plugin no `nativeLibraryDir`, so this
     * goes by the package id (DESIGN.md 1): the aarch64 build is 64-bit, ra32 is 32-bit, and the universal id takes the
     * device's own preference ([deviceAbis] in order).
     */
    fun abiFor(pkg: String, deviceAbis: List<String>): String? = when (pkg) {
        "com.retroarch.aarch64" -> deviceAbis.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
        "com.retroarch.ra32" -> deviceAbis.firstOrNull { it == "armeabi-v7a" || it == "x86" }
        else -> listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86").firstOrNull { it in deviceAbis }
    }

    /**
     * True when [header] (the file's first bytes) and [size] describe a whole ELF shared object for [abi]: the magic,
     * the class and machine of the ABI, and a section header table that ends inside the file (a cut-off download
     * does not).
     */
    fun isWholeElf(header: ByteArray, size: Long, abi: String): Boolean {
        if (header.size < 52 || header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return false
        if (header[5] != 1.toByte()) return false // little-endian, as every Android ABI
        val is64 = header[4] == 2.toByte()
        val (wantClass64, machine) = when (abi) {
            "arm64-v8a" -> true to 183
            "armeabi-v7a" -> false to 40
            "x86_64" -> true to 62
            "x86" -> false to 3
            else -> return false
        }
        if (is64 != wantClass64 || (is64 && header.size < 64)) return false
        fun le(at: Int, n: Int): Long = (0 until n).fold(0L) { acc, i -> acc or ((header[at + i].toLong() and 0xff) shl (8 * i)) }
        if (le(18, 2).toInt() != machine) return false
        val shoff = if (is64) le(40, 8) else le(32, 4)
        val shentsize = if (is64) le(58, 2) else le(46, 2)
        val shnum = if (is64) le(60, 2) else le(48, 2)
        return shoff > 0 && shentsize > 0 && shoff + shentsize * shnum <= size
    }

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
