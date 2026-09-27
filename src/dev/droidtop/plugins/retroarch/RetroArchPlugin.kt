package dev.droidtop.plugins.retroarch

import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginEvent
import dev.droidtop.pluginhost.PluginJobProgress
import dev.droidtop.pluginhost.PluginResult
import android.os.Build
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Integrates an installed RetroArch into droidtop (docs/SPEC.md 12a).
 * Every fact this class relies on -- package ids, Intent extras, the
 * network command port and its commands, buildbot's layout, which
 * directories are app-private vs. shared storage -- is cited against
 * RetroArch's own source in this repo's DESIGN.md; nothing here is
 * guessed.
 *
 * Root (`requestsRoot: true` in manifest.template.json) is an
 * enhancement only: every action below has a non-root path documented
 * in DESIGN.md 7, and this class never throws just because
 * [PluginContext.hasRootApproval] is false.
 */
/** Thrown from inside the download loop when DroidtopPlugin.cancelJob asked this job to stop -- caught in runDownloadCoreJob, same as any other download failure. */
private class CancellationException(message: String) : Exception(message)

class RetroArchPlugin : DroidtopPlugin {
    private lateinit var context: PluginContext

    // Cancellation for the one job this plugin supports (docs/SPEC.md
    // 12a "Jobs": DroidtopPlugin.cancelJob is best-effort by default --
    // this plugin makes it a REAL best-effort by checking the flag
    // inside its own download loop, since HttpURLConnection's stream
    // read has no other cooperative cancellation point). One in-flight
    // job at a time is the only case this plugin's own startJob ever
    // creates (JOB_DOWNLOAD_CORE), so a single id/flag pair is enough --
    // no need for a map keyed by jobId.
    @Volatile private var activeDownloadJobId: String? = null
    @Volatile private var cancelRequested: Boolean = false

    override fun onLoad(context: PluginContext) {
        this.context = context
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult = when (capability) {
        PluginCapability.APP_STATUS -> handleAppStatus(args)
        PluginCapability.STATUS_TILE -> handleStatusTile()
        PluginCapability.SETTINGS_ROWS -> handleSettingsRows()
        else -> PluginResult.failure("RetroArchPlugin does not implement ${capability.id}")
    }

    override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        if (capability != PluginCapability.APP_STATUS || args.string("job") != JOB_DOWNLOAD_CORE) {
            throw UnsupportedOperationException("RetroArchPlugin only supports the '$JOB_DOWNLOAD_CORE' job under app_status")
        }
        activeDownloadJobId = jobId
        cancelRequested = false
        runDownloadCoreJob(args, progress)
    }

    override fun cancelJob(jobId: String) {
        if (jobId == activeDownloadJobId) cancelRequested = true
    }

    // ---------------------------------------------------------------
    // Event hooks (docs/SPEC.md 12a "Event hooks")
    // ---------------------------------------------------------------

    /**
     * Reacts to [PluginEvent.DEFAULT_PLAYER_CHANGED] (this plugin's own
     * subscription, manifest.template.json's `subscribedEvents`): when
     * droidtop just made an installed RetroArch the default player for a
     * system that names a core, and that core isn't downloaded yet, asks
     * droidtop to start [JOB_DOWNLOAD_CORE] for it -- the real,
     * cited reason this plugin needed the event mechanism built at all
     * (droidtop docs/SPEC.md 12a). No-ops (plain success, no `startJob`)
     * for every other case: RetroArch not installed, the event naming a
     * different player's package, no core configured for that system, or
     * the core already downloaded.
     */
    override fun onEvent(event: PluginEvent, args: PluginArgs): PluginResult {
        if (event != PluginEvent.DEFAULT_PLAYER_CHANGED) return PluginResult.success()
        val installed = detectInstalledPackage() ?: return PluginResult.success()
        val playerPackage = args.string("playerPackage").orEmpty()
        if (playerPackage != installed) return PluginResult.success()
        val core = args.string("core").orEmpty()
        if (core.isBlank()) return PluginResult.success()
        if (core in listDownloadedCores()) return PluginResult.success(mapOf("note" to "core '$core' already downloaded"))
        return PluginResult.success(
            mapOf(
                "startJob" to PluginCapability.APP_STATUS.id,
                "job" to JOB_DOWNLOAD_CORE,
                "core" to core,
            ),
        )
    }

    // ---------------------------------------------------------------
    // app_status
    // ---------------------------------------------------------------

    private fun handleAppStatus(args: PluginArgs): PluginResult = when (args.stringOrDefault("action", "status")) {
        "status" -> statusResult()
        "launch" -> launch(args)
        "load_core" -> sendNetworkCommand("LOAD_CORE " + args.string("corePath").orEmpty())
        else -> PluginResult.failure("unknown app_status action '" + args.string("action") + "'")
    }

    private fun statusResult(): PluginResult {
        val installed = detectInstalledPackage()
        val values = mutableMapOf(
            "installed" to (installed != null).toString(),
            "rootApproved" to context.hasRootApproval().toString(),
            "shizukuAvailable" to context.hasShizukuAccess().toString(),
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
    private fun detectInstalledPackage(): String? =
        listOf(PACKAGE_AARCH64, PACKAGE_RA32, PACKAGE_UNIVERSAL).firstOrNull { context.isAppInstalled(it) }

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
     * DESIGN.md 2: droidtop's plugin-host now ships
     * PluginContext.launchAppWithExtras (merged into Droidtop/droidtop
     * main 2026-09-27, commit 87fccc4c), so RetroArch's own ROM/LIBRETRO/
     * CONFIGFILE extras attach directly -- no more reflection bridge.
     */
    private fun launchWithExtras(pkg: String, extras: Map<String, String>): Boolean =
        if (extras.isEmpty()) context.launchApp(pkg) else context.launchAppWithExtras(pkg, extras)

    // ---------------------------------------------------------------
    // status_tile / settings_rows
    // ---------------------------------------------------------------

    private fun handleStatusTile(): PluginResult {
        val installed = detectInstalledPackage()
        val value = when {
            installed == null -> "not installed"
            else -> "installed (" + installed + "), " + listDownloadedCores().size + " core(s) fetched"
        }
        return PluginResult.success(mapOf("label" to "RetroArch", "value" to value))
    }

    /**
     * droidtop has no generic settings_rows renderer yet (SPEC.md 12a:
     * built capabilities list does not include a settings_rows consumer
     * screen) -- there is no richer row schema to target yet, so this
     * returns the same flat label/value shape status_tile already uses,
     * one row per fact, keyed so a future renderer can tell them apart.
     * Kept intentionally simple rather than inventing a schema droidtop
     * itself has not decided on.
     */
    private fun handleSettingsRows(): PluginResult {
        val installed = detectInstalledPackage()
        val values = mutableMapOf(
            "row_retroarch" to (installed?.let { "RetroArch: installed ($it)" } ?: "RetroArch: not installed"),
            "row_root" to if (context.hasRootApproval()) "Root: approved, cores install directly" else "Root: not used, core installs go through RetroArch's own Online Updater",
            "row_cores" to ("Downloaded by this plugin: " + listDownloadedCores().joinToString(", ").ifEmpty { "none" }),
        )
        return PluginResult.success(values)
    }

    // ---------------------------------------------------------------
    // Core download (buildbot.libretro.com, DESIGN.md 6-7)
    // ---------------------------------------------------------------

    private fun runDownloadCoreJob(args: PluginArgs, progress: PluginJobProgress) {
        val core = args.string("core")
        if (core.isNullOrBlank()) {
            progress.complete(PluginResult.failure("missing 'core' arg (e.g. 'snes9x')"))
            return
        }
        val abi = preferredAbi()
        if (abi == null) {
            progress.complete(PluginResult.failure("no supported ABI (need arm64-v8a, armeabi-v7a, x86 or x86_64)"))
            return
        }
        progress.report(0, "Downloading $core for $abi from buildbot.libretro.com")
        val destDir = File(context.privateDataDir(), "cores/$abi").apply { mkdirs() }
        val zipFile = File(destDir, core + "_libretro_android.so.zip")
        val soFile = File(destDir, core + "_libretro_android.so")

        try {
            downloadWithProgress(coreZipUrl(abi, core), zipFile) { pct -> progress.report(pct, "Downloading ($pct%)") }
            progress.report(95, "Verifying archive")
            extractSingleSo(zipFile, soFile)
            zipFile.delete()

            val values = mutableMapOf(
                "core" to core,
                "abi" to abi,
                "path" to soFile.absolutePath,
            )

            if (context.hasRootApproval()) {
                progress.report(97, "Root approved: copying into RetroArch's own cores directory")
                val pkg = detectInstalledPackage()
                if (pkg != null && copyIntoRetroArchCoresDirAsRoot(pkg, soFile)) {
                    values["installedIntoRetroArch"] = "true"
                } else {
                    values["installedIntoRetroArch"] = "false"
                    values["note"] = "root copy failed or RetroArch not detected; core is still available at 'path' for the non-root LIBRETRO-extra fallback (see DESIGN.md 7)"
                }
            } else {
                values["installedIntoRetroArch"] = "false"
                values["note"] = "no root approval: open RetroArch's own Main Menu -> Online Updater -> Core Downloader for '$core', or approve root for this plugin to install it directly"
            }

            progress.report(100, "Done")
            progress.complete(PluginResult.success(values))
        } catch (t: Throwable) {
            zipFile.delete()
            progress.complete(PluginResult.failure(t.message ?: "core download failed"))
        }
    }

    private fun listDownloadedCores(): List<String> {
        val coresRoot = File(context.privateDataDir(), "cores")
        if (!coresRoot.isDirectory) return emptyList()
        return coresRoot.listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { abiDir -> abiDir.listFiles { f -> f.name.endsWith("_libretro_android.so") }.orEmpty().toList() }
            .map { it.name.removeSuffix("_libretro_android.so") }
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
        "$BUILDBOT_BASE/$abi/" + core + "_libretro_android.so.zip"

    private fun downloadWithProgress(url: String, dest: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("buildbot returned HTTP " + connection.responseCode + " for " + url)
        }
        val total = connection.contentLengthLong
        var read = 0L
        connection.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var lastReported = -1
                while (true) {
                    // Checked every chunk (up to 64 KiB read, never a
                    // whole file's worth) so a cancel actually stops the
                    // transfer promptly instead of only being honored
                    // between whole downloads.
                    if (cancelRequested) throw CancellationException("download cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = ((read * 90) / total).toInt().coerceIn(0, 90)
                        if (pct != lastReported) {
                            onProgress(pct)
                            lastReported = pct
                        }
                    }
                }
            }
        }
        connection.disconnect()
    }

    /**
     * buildbot publishes no per-core checksum manifest (DESIGN.md 6), so
     * the only verification available here is structural: the archive
     * must be a valid zip containing exactly one entry, and that entry
     * must be the core .so this call asked for.
     */
    private fun extractSingleSo(zipFile: File, destSo: File) {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            val entry = zip.nextEntry ?: throw IllegalStateException("empty zip from buildbot")
            if (!entry.name.endsWith("_libretro_android.so")) {
                throw IllegalStateException("unexpected zip entry '" + entry.name + "', expected a *_libretro_android.so")
            }
            destSo.outputStream().use { out -> zip.copyTo(out) }
            if (zip.nextEntry != null) {
                throw IllegalStateException("zip from buildbot had more than one entry, refusing to guess which is the core")
            }
        }
    }

    /**
     * Root-only enhancement (DESIGN.md 7): RetroArch's own cores dir is
     * app-private (DEFAULT_DIR_CORE = app_dir/cores, platform_unix.c) and
     * app_dir defaults to getApplicationInfo().dataDir -- /data/data/<pkg>
     * or /data/user/0/<pkg> depending on the device (both point at the
     * same inode; platform_unix.c's own comment gives
     * "/data/user/0/com.retroarch.aarch64" as the real-world example).
     * `su -c cp` is the only way another app's UID reaches that path.
     */
    private fun copyIntoRetroArchCoresDirAsRoot(pkg: String, soFile: File): Boolean = runCatching {
        val destDir = "/data/data/$pkg/cores"
        val safePath = soFile.absolutePath.replace("\"", "\\\"")
        val shellCmd = "mkdir -p " + destDir + " && cp \"" + safePath + "\" " + destDir + "/"
        val process = ProcessBuilder("su", "-c", shellCmd).start()
        process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        process.waitFor() == 0
    }.getOrDefault(false)

    // ---------------------------------------------------------------
    // Network command interface (DESIGN.md 5)
    // ---------------------------------------------------------------

    /**
     * Sends a plaintext command over RetroArch's own UDP command socket
     * (command.c, port 55355 by default, only active when the user
     * enabled "Network Commands" in RetroArch's own settings). Only
     * ever targets localhost, matching RetroArch's own trust assumption
     * for this socket (command.c's own comment: "Anyone on that network
     * can then send LOAD_CORE or ...").
     */
    private fun sendNetworkCommand(command: String): PluginResult = runCatching {
        DatagramSocket().use { socket ->
            val bytes = command.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), NETWORK_CMD_PORT)
            socket.send(packet)
        }
        PluginResult.success(mapOf("sent" to command))
    }.getOrElse { PluginResult.failure(it.message ?: "failed to send network command (is RetroArch running with Network Commands enabled?)") }

    companion object {
        // DESIGN.md 1 -- RetroArch's own pkg/android/phoenix/build.gradle product flavors.
        private const val PACKAGE_AARCH64 = "com.retroarch.aarch64"
        private const val PACKAGE_RA32 = "com.retroarch.ra32"
        private const val PACKAGE_UNIVERSAL = "com.retroarch"

        // DESIGN.md 6 -- config.def.h's DEFAULT_BUILDBOT_SERVER_URL, generalised across the per-ABI folders it names.
        private const val BUILDBOT_BASE = "https://buildbot.libretro.com/nightly/android/latest"

        // DESIGN.md 5 -- command.h's DEFAULT_NETWORK_CMD_PORT.
        private const val NETWORK_CMD_PORT = 55355

        const val JOB_DOWNLOAD_CORE = "download_core"
    }
}
