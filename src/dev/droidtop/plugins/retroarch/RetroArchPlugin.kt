package dev.droidtop.plugins.retroarch

import android.os.Build
import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.LegacyHandle
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginEvent
import dev.droidtop.pluginhost.PluginJobProgress
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.PluginResult
import android.os.ParcelFileDescriptor
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Integrates an installed RetroArch into droidtop (docs/SPEC.md 12a). Every fact this class relies on -- package ids,
 * Intent extras, the network command port and its commands, buildbot's layout, which directories are app-private vs.
 * shared storage -- is cited against RetroArch's own source in this repo's DESIGN.md; nothing here is guessed.
 *
 * Contract 2 (docs/plugin-api.md): the plugin's control point is its Quick Menu panel (`ui.panel`), drawn by droidtop
 * from the view [RetroArchViews] returns, with a settings page (`ui.settings`), a status tile and the app bridge
 * (`apps.bridge`) behind it. Getting a core into RetroArch's own folder is something only root can do, and the plugin
 * never runs `su`: it asks a root helper plugin the user allowed (`priv.shell`, optional, docs/plugin-api.md 2.7), and
 * does less when there is none -- it never throws just because there is no helper.
 *
 * It runs contained (droidtop docs/plugin-api.md 5.3): an isolated process with no network and no files of its own.
 * Cores come down through droidtop (`net.download`, buildbot.libretro.com only, as declared), land in the plugin's own
 * data kept by droidtop (`data.*`), are read and written there through descriptors droidtop hands over, and reach
 * RetroArch's folder through the root helper, given droidtop's path of the file (`data.path`). Its settings and the
 * systems it heard about live in the same data.
 */
/** Thrown from inside the download loop when DroidtopPlugin.cancelJob asked this job to stop -- caught in runInstallCoreJob, same as any other download failure. */
private class CancellationException(message: String) : Exception(message)

class RetroArchPlugin : DroidtopPlugin {
    // Cancellation keyed by jobId, not one flag -- concurrent install_core jobs really do happen.
    // A shared flag would let cancelling one job silently cancel every other job in flight too;
    // each jobId gets its own entry here, checked from inside that job's own download loop.
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()
    private lateinit var context: PluginContext
    private lateinit var state: RetroArchState

    override fun onLoad(context: PluginContext) {
        this.context = context
        this.state = RetroArchState(dataFiles)
    }

    // ---------------------------------------------------------------
    // droidtop's broker: the plugin's only way out of its process
    // ---------------------------------------------------------------

    /** One broker call and its parsed reply; a reply that does not parse is a FAILED one, never an exception. */
    private fun call(api: String, op: String, args: JSONObject = JSONObject()): JSONObject = runCatching {
        JSONObject(context.call(api, 1, op, args.toString()))
    }.getOrElse { JSONObject().put("ok", false).put("error", JSONObject().put("code", "FAILED").put("message", it.message ?: "no reply")) }

    private fun JSONObject.why(): String = optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "droidtop gave no reason"

    /** The plugin's small JSON files, by name in its own data kept by droidtop. */
    private val dataFiles = object : PluginFiles {
        override fun read(name: String): String? {
            val reply = call("data", "read", JSONObject().put("name", name))
            return if (reply.optBoolean("ok")) reply.optJSONObject("data")?.optString("text") else null
        }

        override fun write(name: String, text: String) {
            call("data", "write", JSONObject().put("name", name).put("text", text))
        }
    }

    /** One of the plugin's own files as a descriptor (mode r or w), or why droidtop would not open it. */
    private fun openData(name: String, mode: String): ParcelFileDescriptor {
        val opened = context.openFile("data", 1, "open", JSONObject().put("name", name).put("mode", mode).toString())
        return opened.fd ?: throw IllegalStateException("could not open $name: " + runCatching { JSONObject(opened.reply).why() }.getOrDefault("no reply"))
    }

    private fun deleteData(name: String) {
        call("data", "delete", JSONObject().put("name", name))
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult = when (capability) {
        PluginCapability.APP_STATUS -> handleAppStatus(args)
        PluginCapability.STATUS_TILE -> handleStatusTile()
        else -> PluginResult.failure("RetroArchPlugin does not implement ${capability.id}")
    }

    /**
     * The contract 2 entry point: the panel and the settings page are this plugin's own; the status tile and the app
     * bridge are still the contract 1 capabilities they replaced, served through [invoke] by the host's translation.
     */
    override fun handle(call: PluginCall): PluginReply = when (call.point) {
        POINT_PANEL, POINT_SETTINGS -> handlePage(call)
        else -> LegacyHandle.translate(this, call)
    }

    override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        val envelope = args.string("call")?.let { PluginCall.fromJson(it) }
        val core: String?
        val automatic: Boolean
        if (envelope != null) {
            if (envelope.op != RetroArchViews.OP_INSTALL_CORE) {
                throw UnsupportedOperationException("RetroArchPlugin only supports the '${RetroArchViews.OP_INSTALL_CORE}' job from its pages")
            }
            // A core named by a row, else the one typed into the "Core name" field.
            val values = envelope.args.optJSONObject("values")
            core = envelope.args.optString("core").ifBlank { values?.optString(RetroArchViews.INPUT_CORE_NAME).orEmpty() }.trim()
            automatic = false
        } else {
            if (capability != PluginCapability.APP_STATUS || args.string("job") != JOB_DOWNLOAD_CORE) {
                throw UnsupportedOperationException("RetroArchPlugin only supports the '$JOB_DOWNLOAD_CORE' job under app_status")
            }
            core = args.string("core")?.trim()
            // Set by onEvent: a download droidtop started for the user, not one the user asked for.
            automatic = args.string("auto") == "true"
        }
        cancelledJobs.remove(jobId)
        runInstallCoreJob(jobId, core, automatic, progress)
    }

    override fun cancelJob(jobId: String) {
        cancelledJobs.add(jobId)
    }

    // ---------------------------------------------------------------
    // Event hooks (docs/SPEC.md 12a "Event hooks")
    // ---------------------------------------------------------------

    /**
     * Reacts to [PluginEvent.DEFAULT_PLAYER_CHANGED] (this plugin's own subscription): remembers which systems
     * RetroArch plays, for the panel, and when droidtop just made an installed RetroArch the default player for a
     * system that names a core, and that core isn't downloaded yet, asks droidtop to start [JOB_DOWNLOAD_CORE] for it
     * -- the real, cited reason this plugin needed the event mechanism built at all (droidtop docs/SPEC.md 12a). That
     * last part follows the "Download a core when you choose RetroArch" switch. No-ops (plain success, no `startJob`)
     * for every other case: RetroArch not installed, the event naming a different player's package, no core
     * configured for that system, or the core already downloaded.
     */
    override fun onEvent(event: PluginEvent, args: PluginArgs): PluginResult {
        if (event != PluginEvent.DEFAULT_PLAYER_CHANGED) return PluginResult.success()
        val systemId = args.string("systemId").orEmpty()
        val playerPackage = args.string("playerPackage").orEmpty()
        val installed = detectInstalledPackage()
        // The panel lists the systems RetroArch plays; a system that moved to another player leaves the list.
        if (systemId.isNotBlank()) {
            if (playerPackage in RETROARCH_PACKAGES) {
                state.rememberSystem(SystemCore(systemId, args.string("systemName").orEmpty().ifBlank { systemId }, args.string("core").orEmpty(), playerPackage))
            } else {
                state.forgetSystem(systemId)
            }
        }
        installed ?: return PluginResult.success()
        if (playerPackage != installed) return PluginResult.success()
        val core = args.string("core").orEmpty()
        if (core.isBlank() || !RetroArchCores.isValidName(core)) return PluginResult.success()
        if (!state.settings().autoDownload) return PluginResult.success(mapOf("note" to "automatic downloads are off"))
        if (core in listDownloadedCores()) return PluginResult.success(mapOf("note" to "core '$core' already downloaded"))
        return PluginResult.success(
            mapOf(
                "startJob" to PluginCapability.APP_STATUS.id,
                "job" to JOB_DOWNLOAD_CORE,
                "core" to core,
                "auto" to "true",
            ),
        )
    }

    // ---------------------------------------------------------------
    // The panel and the settings page (ui.panel, ui.settings)
    // ---------------------------------------------------------------

    private fun handlePage(call: PluginCall): PluginReply = when (call.op) {
        "panel" -> PluginReply.ok(RetroArchViews.panel(model()))
        "view" -> PluginReply.ok(RetroArchViews.settingsPage(model()))
        RetroArchViews.OP_SAVE -> save(call.args.optJSONObject("values"))
        RetroArchViews.OP_LAUNCH -> {
            val result = launch(PluginArgs(emptyMap()))
            if (result.ok) PluginReply.ok(JSONObject().put("message", "Opening RetroArch")) else PluginReply.error(PluginErrorCode.FAILED, result.error ?: "RetroArch did not open")
        }
        RetroArchViews.OP_SCAN -> scanRetroArch()
        else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "Unsupported op: ${call.op}")
    }

    private fun model(): PanelModel {
        val fromLibrary = librarySystems()
        return PanelModel(
            installedPackage = detectInstalledPackage(),
            systems = fromLibrary ?: state.systems(),
            systemsFromLibrary = fromLibrary != null,
            downloaded = listDownloadedCores().toSet(),
            inRetroArch = state.scan()?.cores,
            elevated = elevatedAvailable(),
            settings = state.settings(),
        )
    }

    /**
     * Every system droidtop has games for that RetroArch plays (`library.read` `systems`, docs/plugin-api.md A1), or
     * null when droidtop did not give the list: the user has not allowed "See your library", this droidtop predates the
     * call, or its library is not loaded yet. The panel then shows the systems heard through the event instead.
     */
    private fun librarySystems(): List<SystemCore>? = runCatching {
        val reply = JSONObject(context.call("library.read", 1, "systems", "{}"))
        if (!reply.optBoolean("ok")) null else LibrarySystems.retroArchSystems(reply.optJSONObject("data"), RETROARCH_PACKAGES)
    }.getOrNull()

    private fun save(values: JSONObject?): PluginReply {
        val current = state.settings()
        fun flag(key: String, fallback: Boolean): Boolean = when (values?.optString(key)) {
            "true" -> true
            "false" -> false
            else -> fallback
        }
        state.saveSettings(
            RetroArchSettings(
                autoDownload = flag(RetroArchViews.TOGGLE_AUTO_DOWNLOAD, current.autoDownload),
                autoInstall = flag(RetroArchViews.TOGGLE_AUTO_INSTALL, current.autoInstall),
            ),
        )
        return PluginReply.ok(JSONObject().put("message", "Saved"))
    }

    // ---------------------------------------------------------------
    // The elevated path: a root helper plugin, never `su` (docs/plugin-api.md 2.7)
    // ---------------------------------------------------------------

    /** True while a running plugin offers `priv.shell` at root level. Harmless to ask: it needs no grant. */
    private fun elevatedAvailable(): Boolean = runCatching {
        val reply = JSONObject(context.call("plugins", 1, "available", JSONObject().put("api", "priv.shell").put("minLevel", "root").toString()))
        reply.optBoolean("ok") && reply.optJSONObject("data")?.optBoolean("available") == true
    }.getOrDefault(false)

    /** One command through the helper, run directly (no shell). [error] is why it could not run at all, e.g. the user has not allowed root yet. */
    private class Exec(val exit: Int, val stdout: String, val stderr: String, val error: String?) {
        val ok: Boolean get() = error == null && exit == 0
        fun why(): String = error ?: stderr.trim().ifEmpty { "exit $exit" }
    }

    private fun exec(vararg argv: String): Exec = runCatching {
        val reply = JSONObject(context.call("priv.shell", 1, "exec", JSONObject().put("argv", JSONArray(argv.toList())).toString()))
        if (!reply.optBoolean("ok")) {
            Exec(-1, "", "", reply.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "the root helper refused")
        } else {
            val data = reply.optJSONObject("data") ?: JSONObject()
            Exec(data.optInt("exit", -1), data.optString("stdout"), data.optString("stderr"), null)
        }
    }.getOrElse { Exec(-1, "", "", it.message ?: "the root helper could not be reached") }

    /** RetroArch's own cores folder (DESIGN.md 7): app-private, so only the helper reaches it. */
    private fun coresDir(pkg: String) = "/data/data/$pkg/cores"

    /** The "Check what RetroArch has" button: lists RetroArch's cores folder through the helper and keeps the answer for the panel. */
    private fun scanRetroArch(): PluginReply {
        val pkg = detectInstalledPackage() ?: return PluginReply.error(PluginErrorCode.FAILED, "RetroArch is not installed")
        val listing = exec("ls", coresDir(pkg))
        if (!listing.ok) return PluginReply.error(PluginErrorCode.FAILED, "Could not look in RetroArch's cores folder: ${listing.why()}")
        val cores = listing.stdout.lineSequence().mapNotNull { RetroArchCores.nameOf(it.trim()) }.toSet()
        state.saveScan(CoreScan(pkg, cores))
        return PluginReply.ok(JSONObject().put("message", "RetroArch has ${cores.size} core(s)"))
    }

    /**
     * Puts the downloaded core [soName] (a name in the plugin's data) into RetroArch's cores folder as root and gives it
     * the same owner RetroArch's own files have, which is what lets RetroArch load it (a file copied by root is root's,
     * and RetroArch's user cannot read it). The helper gets droidtop's path of the file: the plugin itself cannot open a
     * path. Each step stops the install at its own error; the file is still downloaded for the manual way (DESIGN.md 7).
     */
    private fun installAsRoot(pkg: String, core: String, soName: String): String? {
        val located = call("data", "path", JSONObject().put("name", soName))
        if (!located.optBoolean("ok")) return "could not find the downloaded core: ${located.why()}"
        val source = located.optJSONObject("data")?.optString("path").orEmpty()
        val dir = coresDir(pkg)
        val dest = "$dir/${RetroArchCores.fileName(core)}"
        exec("mkdir", "-p", dir).takeIf { !it.ok }?.let { return "could not create RetroArch's cores folder: ${it.why()}" }
        exec("cp", source, dest).takeIf { !it.ok }?.let { return "could not copy the core: ${it.why()}" }
        val owner = exec("stat", "-c", "%u:%g", "/data/data/$pkg")
        if (!owner.ok || !OWNER.matches(owner.stdout.trim())) return "could not read RetroArch's user: ${owner.why()}"
        exec("chown", owner.stdout.trim(), dest).takeIf { !it.ok }?.let { return "could not give the core to RetroArch: ${it.why()}" }
        exec("chmod", "755", dest).takeIf { !it.ok }?.let { return "could not make the core loadable: ${it.why()}" }
        // The label a new file in an app's folder should carry; a device without restorecon simply keeps the inherited one.
        exec("restorecon", dest)
        return null
    }

    // ---------------------------------------------------------------
    // app_status
    // ---------------------------------------------------------------

    private fun handleAppStatus(args: PluginArgs): PluginResult = when (args.stringOrDefault("action", "status")) {
        "status" -> statusResult()
        "launch" -> launch(args)
        else -> PluginResult.failure("unknown app_status action '" + args.string("action") + "'")
    }

    private fun statusResult(): PluginResult {
        val installed = detectInstalledPackage()
        val values = mutableMapOf(
            "installed" to (installed != null).toString(),
            "elevatedInstall" to elevatedAvailable().toString(),
        )
        installed?.let {
            values["package"] = it
            values["likelyBuildbotBuild"] = (it != PACKAGE_UNIVERSAL).toString()
        }
        val downloaded = listDownloadedCores()
        values["downloadedCoreCount"] = downloaded.size.toString()
        if (downloaded.isNotEmpty()) values["downloadedCores"] = downloaded.joinToString(",")
        // Generic app_status job-offer convention (droidtop docs/SPEC.md
        // 12a, PluginAppStatus's own doc comment): names JOB_DOWNLOAD_CORE
        // as a one-text-field job droidtop's generic app_status screen
        // can offer without droidtop knowing anything RetroArch-specific.
        values["job"] = JOB_DOWNLOAD_CORE
        values["jobArgKey"] = "core"
        values["jobLabel"] = "Download a core by name (e.g. snes9x)"
        return PluginResult.success(values)
    }

    /**
     * Checks RetroArch's three real Android package ids in order
     * (DESIGN.md 1, from RetroArch's own pkg/android/phoenix/build.gradle
     * product flavors): the 64-bit-only build first, then 32-bit, then
     * the universal/Play Store id last since it is the least specific.
     */
    private fun detectInstalledPackage(): String? = RETROARCH_PACKAGES.firstOrNull { context.isAppInstalled(it) }

    /**
     * Launches RetroArch with whatever of ROM/LIBRETRO/CONFIGFILE the
     * caller supplied, via the intent extras RetroArch's own
     * RetroActivityFuture/platform_unix.c read (DESIGN.md 2). Falls back
     * to a bare launch (still correct: platform_unix.c derives every
     * value itself when the extra is absent) when the caller gave
     * nothing plugin-specific to aim at.
     */
    private fun launch(args: PluginArgs): PluginResult {
        val pkg = detectInstalledPackage() ?: return PluginResult.failure("RetroArch is not installed")
        val extras = buildMap {
            args.string("rom")?.let { put("ROM", it) }
            args.string("core")?.let { put("LIBRETRO", it) }
            args.string("config")?.let { put("CONFIGFILE", it) }
        }
        val launched = launchWithExtras(pkg, extras)
        return if (launched) {
            PluginResult.success(mapOf("launched" to "true", "package" to pkg))
        } else {
            PluginResult.failure("launchApp failed for $pkg")
        }
    }

    /**
     * DESIGN.md 2: droidtop's plugin-host ships PluginContext.launchAppWithExtras, so RetroArch's own
     * ROM/LIBRETRO/CONFIGFILE extras attach directly -- no reflection bridge.
     */
    private fun launchWithExtras(pkg: String, extras: Map<String, String>): Boolean =
        if (extras.isEmpty()) context.launchApp(pkg) else context.launchAppWithExtras(pkg, extras)

    // ---------------------------------------------------------------
    // status_tile
    // ---------------------------------------------------------------

    private fun handleStatusTile(): PluginResult {
        val installed = detectInstalledPackage()
        val value = when {
            installed == null -> "not installed"
            else -> "installed (" + installed + "), " + listDownloadedCores().size + " core(s) fetched"
        }
        return PluginResult.success(mapOf("label" to "RetroArch", "value" to value))
    }

    // ---------------------------------------------------------------
    // Core download and install (buildbot.libretro.com, DESIGN.md 6-7)
    // ---------------------------------------------------------------

    private fun runInstallCoreJob(jobId: String, core: String?, automatic: Boolean, progress: PluginJobProgress) {
        if (core.isNullOrBlank()) {
            progress.complete(PluginResult.failure("missing core name (e.g. 'snes9x')"))
            return
        }
        // The name goes into an address, a file name and helper commands: only a core's short name is accepted.
        if (!RetroArchCores.isValidName(core)) {
            progress.complete(PluginResult.failure("'$core' is not a core name (letters, digits, - and _ only)"))
            return
        }
        val abi = preferredAbi()
        if (abi == null) {
            progress.complete(PluginResult.failure("no supported ABI (need arm64-v8a, armeabi-v7a, x86 or x86_64)"))
            return
        }
        progress.report(0, "Downloading $core for $abi from buildbot.libretro.com")
        // Names in the plugin's own data. Staged per-jobId so two concurrent downloads (different cores, or -- as seen
        // live on the rig -- the same core started twice) never share a zip/so name and race each other's delete or
        // read; only the FINAL name is shared, and it is only ever reached by a rename (data.move) once a job's own
        // download and extract fully succeeded.
        val dir = "$CORES_DIR/$abi"
        val zipName = "$dir/$core.$jobId.so.zip"
        val stagedName = "$dir/$core.$jobId.so"
        val soName = "$dir/${RetroArchCores.fileName(core)}"

        try {
            downloadWithProgress(jobId, coreZipUrl(abi, core), zipName) { pct -> progress.report(pct, "Downloading ($pct%)") }
            progress.report(95, "Verifying archive")
            extractSingleSo(zipName, stagedName)
            deleteData(zipName)
            val moved = call("data", "move", JSONObject().put("from", stagedName).put("to", soName))
            if (moved.optJSONObject("data")?.optBoolean("moved") != true) throw IllegalStateException("could not keep the core: ${moved.why()}")

            val values = mutableMapOf(
                "core" to core,
                "abi" to abi,
                "file" to soName,
            )
            val pkg = detectInstalledPackage()
            // A core a person asked for is always put in place when it can be; one droidtop fetched on its own follows the switch.
            val install = !automatic || state.settings().autoInstall
            when {
                pkg == null -> {
                    values["installedIntoRetroArch"] = "false"
                    values["note"] = "RetroArch is not installed; the core is downloaded and waits for it"
                }
                !install -> {
                    values["installedIntoRetroArch"] = "false"
                    values["note"] = "downloaded; putting cores into RetroArch automatically is off"
                }
                !elevatedAvailable() -> {
                    values["installedIntoRetroArch"] = "false"
                    values["note"] = "downloaded; no root helper is allowed, so open RetroArch's Main Menu, Online Updater, Core Downloader for '$core'"
                }
                else -> {
                    progress.report(97, "Putting $core into RetroArch")
                    val problem = installAsRoot(pkg, core, soName)
                    if (problem == null) {
                        values["installedIntoRetroArch"] = "true"
                        state.saveScan(CoreScan(pkg, (state.scan()?.takeIf { it.pkg == pkg }?.cores.orEmpty()) + core))
                    } else {
                        values["installedIntoRetroArch"] = "false"
                        values["note"] = "downloaded, but $problem. Use RetroArch's Main Menu, Online Updater, Core Downloader for '$core'"
                    }
                }
            }
            values["message"] = if (values["installedIntoRetroArch"] == "true") "$core is in RetroArch" else "$core downloaded"
            progress.report(100, "Done")
            progress.complete(PluginResult.success(values))
        } catch (t: Throwable) {
            deleteData(zipName)
            deleteData(stagedName)
            progress.complete(PluginResult.failure(t.message ?: "core download failed"))
        } finally {
            cancelledJobs.remove(jobId)
        }
    }

    /** The cores downloaded so far, from the plugin's own data (`cores/<abi>/<core>_libretro_android.so`). */
    private fun listDownloadedCores(): List<String> {
        val reply = call("data", "list", JSONObject().put("prefix", "$CORES_DIR/"))
        val files = reply.optJSONObject("data")?.optJSONArray("files") ?: return emptyList()
        return (0 until files.length())
            .mapNotNull { files.optJSONObject(it)?.optString("name")?.substringAfterLast('/') }
            .mapNotNull { RetroArchCores.nameOf(it) }
            .distinct()
            .sorted()
    }

    /**
     * DESIGN.md 1: aarch64 build ships arm64-v8a+x86_64, ra32 ships
     * armeabi-v7a+x86, the universal build prefers 64-bit. This plugin
     * mirrors that preference order for ITS OWN download regardless of
     * which RetroArch package is installed, since the same core file
     * works with any of the three as long as the ABI matches the
     * device's own supported ABI list.
     */
    private fun preferredAbi(): String? {
        val supported = Build.SUPPORTED_ABIS.toSet()
        return listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86").firstOrNull { it in supported }
    }

    private fun coreZipUrl(abi: String, core: String): String =
        "$BUILDBOT_BASE/$abi/" + RetroArchCores.fileName(core) + ".zip"

    /**
     * Downloads [url] into [name] of the plugin's data through droidtop (`net.download`: droidtop checks it against the
     * declared buildbot.libretro.com, follows any redirect itself and logs it) and waits for that job, passing its
     * progress on as 0 to 90. A cancel of this plugin's job stops droidtop's download too.
     */
    private fun downloadWithProgress(jobId: String, url: String, name: String, onProgress: (Int) -> Unit) {
        val started = call("net", "download", JSONObject().put("url", url).put("name", name))
        if (!started.optBoolean("ok")) throw IllegalStateException("droidtop did not download the core: ${started.why()}")
        val hostJob = started.optJSONObject("data")?.optString("jobId").orEmpty()
        var lastReported = -1
        while (true) {
            if (jobId in cancelledJobs) {
                call("plugins", "job_cancel", JSONObject().put("jobId", hostJob))
                throw CancellationException("download cancelled")
            }
            val status = call("plugins", "job_status", JSONObject().put("jobId", hostJob))
            if (!status.optBoolean("ok")) throw IllegalStateException("lost the download: ${status.why()}")
            val data = status.optJSONObject("data") ?: JSONObject()
            if (data.optBoolean("done")) {
                if (!data.optBoolean("ok")) throw IllegalStateException(data.optString("message").ifBlank { "the download failed" })
                return
            }
            val percent = data.optInt("percent", -1)
            if (percent >= 0) {
                val pct = (percent * 90 / 100).coerceIn(0, 90)
                if (pct != lastReported) {
                    onProgress(pct)
                    lastReported = pct
                }
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * buildbot publishes no per-core checksum manifest (DESIGN.md 6), so
     * the only verification available here is structural: the archive
     * must be a valid zip containing exactly one entry, and that entry
     * must be the core .so this call asked for. Both files are the
     * plugin's own data, read and written through descriptors droidtop
     * hands over.
     */
    private fun extractSingleSo(zipName: String, soName: String) {
        java.util.zip.ZipInputStream(ParcelFileDescriptor.AutoCloseInputStream(openData(zipName, "r")).buffered()).use { zip ->
            val entry = zip.nextEntry ?: throw IllegalStateException("empty zip from buildbot")
            if (!entry.name.endsWith("_libretro_android.so")) {
                throw IllegalStateException("unexpected zip entry '" + entry.name + "', expected a *_libretro_android.so")
            }
            ParcelFileDescriptor.AutoCloseOutputStream(openData(soName, "w")).use { out -> zip.copyTo(out) }
            if (zip.nextEntry != null) {
                throw IllegalStateException("zip from buildbot had more than one entry, refusing to guess which is the core")
            }
        }
    }

    companion object {
        private const val POINT_PANEL = "ui.panel"
        private const val POINT_SETTINGS = "ui.settings"

        // DESIGN.md 1 -- RetroArch's own pkg/android/phoenix/build.gradle product flavors, most specific first.
        private const val PACKAGE_AARCH64 = "com.retroarch.aarch64"
        private const val PACKAGE_RA32 = "com.retroarch.ra32"
        private const val PACKAGE_UNIVERSAL = "com.retroarch"
        private val RETROARCH_PACKAGES = listOf(PACKAGE_AARCH64, PACKAGE_RA32, PACKAGE_UNIVERSAL)

        // DESIGN.md 6 -- config.def.h's DEFAULT_BUILDBOT_SERVER_URL, generalised across the per-ABI folders it names.
        private const val BUILDBOT_BASE = "https://buildbot.libretro.com/nightly/android/latest"

        /** Where downloaded cores live in the plugin's own data: `cores/<abi>/<core>_libretro_android.so`. */
        private const val CORES_DIR = "cores"

        /** How often a running download is asked how far it is. */
        private const val POLL_MS = 250L

        /** `stat -c %u:%g` of RetroArch's data folder: numbers only, so nothing else reaches `chown`. */
        private val OWNER = Regex("^[0-9]+:[0-9]+$")

        const val JOB_DOWNLOAD_CORE = "download_core"
    }
}
