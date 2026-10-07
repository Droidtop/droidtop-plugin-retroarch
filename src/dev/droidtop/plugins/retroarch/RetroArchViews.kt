package dev.droidtop.plugins.retroarch

import org.json.JSONArray
import org.json.JSONObject

/** Everything the panel and the settings page show, gathered by the plugin so the views below are plain data in, data out. */
internal data class PanelModel(
    /** The installed RetroArch package, or null. */
    val installedPackage: String?,
    val systems: List<SystemCore>,
    /** True when [systems] is droidtop's own list of every system with games, false when it is only the ones heard through the event. */
    val systemsFromLibrary: Boolean,
    /** Cores this plugin downloaded. */
    val downloaded: Set<String>,
    /** Cores found in RetroArch's own folder at the last look, or null when the user has not looked. */
    val inRetroArch: Set<String>?,
    /** Whether an allowed root helper can put a core into RetroArch's folder. */
    val elevated: Boolean,
    val settings: RetroArchSettings,
)

/**
 * The plugin's pages as droidtop view documents (docs/plugin-api.md 1.6): droidtop draws them, so they look and move
 * like the rest of the Quick Menu and Settings. Ops the pages call back: `launch`, `scan`, `save` and the job
 * `install_core`.
 */
internal object RetroArchViews {
    const val OP_LAUNCH = "launch"
    const val OP_SCAN = "scan"
    const val OP_SAVE = "save"
    const val OP_INSTALL_CORE = "install_core"

    const val INPUT_CORE_NAME = "core_name"
    const val TOGGLE_AUTO_DOWNLOAD = "auto_download"
    const val TOGGLE_AUTO_INSTALL = "auto_install"

    /** The Quick Menu panel: status, the user's systems with their cores, the install action and the settings. */
    fun panel(m: PanelModel): JSONObject = view(
        statusSection(m),
        coresSection(m),
        installSection(m),
        settingsSection(m.settings),
    )

    /** The plugin's own page under Settings: the same settings, then the facts a person looks up rarely. */
    fun settingsPage(m: PanelModel): JSONObject = view(
        settingsSection(m.settings),
        section(
            "about", "About",
            info("package", "RetroArch package", m.installedPackage ?: "Not installed"),
            info(
                "source", "Where cores come from", "buildbot.libretro.com",
                "Downloaded straight from libretro's build server, the same place RetroArch's own Online Updater uses.",
            ),
            info(
                "systems", if (m.systemsFromLibrary) "Systems with RetroArch" else "Systems seen", m.systems.size.toString(),
                if (m.systemsFromLibrary) {
                    "Every system you have games for that RetroArch launches."
                } else {
                    "Systems you chose RetroArch for since this plugin was installed. Allow \"See your library\" for this plugin to list them all."
                },
            ),
        ),
    )

    private fun statusSection(m: PanelModel): JSONObject {
        val pkg = m.installedPackage
        return section(
            "status", null,
            if (pkg != null) info("retroarch", "RetroArch", "Installed", pkg)
            else info("retroarch", "RetroArch", "Not installed", "Install RetroArch yourself; this plugin looks after cores for the copy you have."),
            *listOfNotNull(
                if (pkg != null) button("launch", "Open RetroArch", null, call(OP_LAUNCH)) else null,
            ).toTypedArray(),
        )
    }

    private fun coresSection(m: PanelModel): JSONObject {
        val looked = m.inRetroArch != null
        val rows = m.systems.map { system ->
            val status = RetroArchCores.status(system.core, m.inRetroArch, m.downloaded)
            val canInstall = status == CoreStatus.DOWNLOADED || status == CoreStatus.MISSING
            row(
                id = "system_${system.id}",
                title = system.name,
                subtitle = system.core.ifBlank { "RetroArch has no core chosen for this system" },
                value = RetroArchCores.label(status, looked),
                action = if (canInstall && RetroArchCores.isValidName(system.core)) {
                    job(OP_INSTALL_CORE, JSONObject().put("core", system.core), "Installing ${system.core}")
                } else {
                    null
                },
            )
        }
        val items = rows.ifEmpty {
            listOf(
                info(
                    "no_systems", "No systems yet", null,
                    if (m.systemsFromLibrary) {
                        "None of your systems launches with RetroArch. Choose it as the player for a system and it appears here with its core."
                    } else {
                        "Choose RetroArch as the player for a system and it appears here with its core. Allow \"See your library\" for this plugin to list every system."
                    },
                ),
            )
        }.toMutableList()
        if (m.elevated) {
            items += button(
                "scan", "Check what RetroArch has", "Looks in RetroArch's own cores folder through the root helper you allowed",
                call(OP_SCAN),
            )
        }
        return JSONObject().put("id", "cores").put("title", "Cores for your systems").put("items", JSONArray(items))
    }

    private fun installSection(m: PanelModel): JSONObject = section(
        "install", "Get a core",
        text(INPUT_CORE_NAME, "Core name", "", "For example snes9x or mgba. Downloaded from buildbot.libretro.com."),
        button(
            "install", "Download and install", null,
            job(OP_INSTALL_CORE, JSONObject(), "Installing a core"),
        ),
        if (m.elevated) {
            info("elevated", "Installing into RetroArch", "Available", "A root helper you allowed puts the core where RetroArch looks for it.")
        } else {
            info(
                "elevated", "Installing into RetroArch", "Not available",
                "Without a root helper the core is downloaded only. Install it from RetroArch: Main Menu, Online Updater, Core Downloader.",
            )
        },
    )

    private fun settingsSection(s: RetroArchSettings): JSONObject = section(
        "settings", "Settings",
        toggle(
            TOGGLE_AUTO_DOWNLOAD, "Download a core when you choose RetroArch for a system", s.autoDownload,
            "Only when that system's core is not downloaded yet.",
        ),
        toggle(
            TOGGLE_AUTO_INSTALL, "Put downloaded cores into RetroArch", s.autoInstall,
            "Only with a root helper you allowed. Cores you ask for yourself are always installed.",
        ),
    )

    // ---- the view schema's node and action shapes ----

    private fun view(vararg sections: JSONObject): JSONObject = JSONObject().put("view", 1).put("sections", JSONArray(sections.toList()))

    private fun section(id: String, title: String?, vararg items: JSONObject): JSONObject =
        JSONObject().put("id", id).put("title", title ?: JSONObject.NULL).put("items", JSONArray(items.toList()))

    private fun node(type: String, id: String, title: String, subtitle: String?, value: Any?): JSONObject =
        JSONObject().put("type", type).put("id", id).put("title", title).apply {
            subtitle?.let { put("subtitle", it) }
            value?.let { put("value", it) }
        }

    private fun info(id: String, title: String, value: String?, subtitle: String? = null) = node("info", id, title, subtitle, value)

    private fun text(id: String, title: String, value: String, subtitle: String? = null) = node("text", id, title, subtitle, value)

    private fun toggle(id: String, title: String, value: Boolean, subtitle: String? = null) =
        node("toggle", id, title, subtitle, value).put("action", call(OP_SAVE))

    private fun button(id: String, title: String, subtitle: String?, action: JSONObject) =
        node("button", id, title, subtitle, null).put("action", action)

    private fun row(id: String, title: String, subtitle: String?, value: String?, action: JSONObject?): JSONObject =
        node("row", id, title, subtitle, value).apply { action?.let { put("action", it) } }

    private fun call(op: String): JSONObject = JSONObject().put("kind", "call").put("op", op)

    private fun job(op: String, args: JSONObject, title: String): JSONObject =
        JSONObject().put("kind", "job").put("op", op).put("args", args).put("title", title)
}
