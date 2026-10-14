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

    /**
     * The rows for the running game on droidtop's companion Game tab ([RetroArchGame]): the slot, then save, load
     * (which asks first: `confirm`, droidtop's Ask before load and overwrite), slot down and up, fast-forward, the
     * shader and RetroArch's FPS line. Without an answer from RetroArch, one line saying how to turn its network
     * commands on instead.
     */
    fun gameRows(slot: Int, answering: Boolean): JSONObject {
        if (!answering) {
            return view(
                section(
                    "game", "RetroArch",
                    info(
                        "no_answer", "RetroArch is not answering", null,
                        "Turn on Network Commands in RetroArch's Network settings.",
                    ),
                ),
            )
        }
        fun command(id: String, title: String, command: String, subtitle: String? = null, confirm: String? = null) =
            button(id, title, subtitle, JSONObject().put("kind", "call").put("op", RetroArchGame.OP_COMMAND).put("args", JSONObject().put("command", command)))
                .apply { confirm?.let { put("confirm", it) } }
        return view(
            section(
                "game", "RetroArch",
                info("slot", "Save slot", slot.toString()),
                command("save", "Save state", RetroArchGame.SAVE_STATE, "Into slot $slot"),
                command("load", "Load state", RetroArchGame.LOAD_STATE, "From slot $slot", confirm = "Load slot $slot? Play since that save is lost."),
                command("slot_down", "Slot down", RetroArchGame.SLOT_MINUS),
                command("slot_up", "Slot up", RetroArchGame.SLOT_PLUS),
                command("fast_forward", "Fast-forward", RetroArchGame.FAST_FORWARD, "On or off"),
            ),
            section(
                "look", "Picture",
                command("shader", "Shader on or off", RetroArchGame.SHADER_TOGGLE),
                command("shader_next", "Next shader", RetroArchGame.SHADER_NEXT),
                command("shader_prev", "Previous shader", RetroArchGame.SHADER_PREV),
                command("fps", "FPS counter", RetroArchGame.FPS_TOGGLE, "On or off"),
            ),
        )
    }

    /** The plugin's own page under Settings: the same settings, then the facts a person looks up rarely. */
    fun settingsPage(m: PanelModel): JSONObject = view(
        settingsSection(m.settings),
        section(
            "about", "About",
            info("package", "RetroArch package", m.installedPackage ?: "Not installed"),
            info("source", "Cores come from", "buildbot.libretro.com"),
            info(
                "systems", if (m.systemsFromLibrary) "Systems with RetroArch" else "Systems seen", m.systems.size.toString(),
                if (m.systemsFromLibrary) null else "Allow \"See your library\" to list them all.",
            ),
        ),
    )

    /** The panel's status tile already says whether RetroArch is installed; this section only adds the way in. */
    private fun statusSection(m: PanelModel): JSONObject =
        if (m.installedPackage != null) {
            section("status", null, button("launch", "Open RetroArch", null, call(OP_LAUNCH)))
        } else {
            section("status", null, info("retroarch", "Get RetroArch", null, "Cores you download wait for it."))
        }

    private fun coresSection(m: PanelModel): JSONObject {
        val looked = m.inRetroArch != null
        val rows = m.systems.map { system ->
            val status = RetroArchCores.status(system.core, m.inRetroArch, m.downloaded)
            val canInstall = status == CoreStatus.DOWNLOADED || status == CoreStatus.MISSING
            row(
                id = "system_${system.id}",
                title = system.name,
                subtitle = system.core.ifBlank { "No core chosen" },
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
                        "Pick RetroArch as a system's player to see its core here."
                    } else {
                        "Pick RetroArch as a system's player, or allow \"See your library\", to see cores here."
                    },
                ),
            )
        }.toMutableList()
        if (m.elevated) {
            items += button(
                "scan", "Check what RetroArch has", null,
                call(OP_SCAN),
            )
        }
        return JSONObject().put("id", "cores").put("title", "Cores for your systems").put("items", JSONArray(items))
    }

    private fun installSection(m: PanelModel): JSONObject = section(
        "install", "Get a core",
        text(INPUT_CORE_NAME, "Core name", "", "For example snes9x or mgba"),
        button(
            "install", if (m.elevated) "Download and install" else "Download", null,
            job(OP_INSTALL_CORE, JSONObject(), "Installing a core"),
        ),
        *listOfNotNull(
            if (m.elevated) null else info("elevated", "Then install it in RetroArch", null, "Online Updater, Core Downloader"),
        ).toTypedArray(),
    )

    private fun settingsSection(s: RetroArchSettings): JSONObject = section(
        "settings", "Settings",
        toggle(TOGGLE_AUTO_DOWNLOAD, "Get cores automatically", s.autoDownload, "When you pick RetroArch for a system"),
        toggle(TOGGLE_AUTO_INSTALL, "Copy cores into RetroArch", s.autoInstall, "Needs a root helper"),
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
