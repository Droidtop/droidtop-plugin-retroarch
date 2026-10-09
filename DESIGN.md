# droidtop-plugin-retroarch — design and source citations

This plugin integrates an installed RetroArch into droidtop (SPEC.md
§12a) as a `native_bundle` plugin. Every claim below is checked against
RetroArch's own source (github.com/libretro/RetroArch) or its own build
files, not guessed and not taken from third-party wikis. Commit hashes
are the ones actually fetched during this research
(`e84ba6b2f97ef94a86d510f0549227e9a624ea96` unless noted, `master` at
research time, 2026-09-27).

## 1. Package identities

RetroArch's Android Gradle module (`pkg/android/phoenix/build.gradle`)
declares `namespace "com.retroarch"` and three real product flavors in
the `variant` dimension:

- `normal` — application id **`com.retroarch`**, no suffix. This is both
  the buildbot's un-suffixed universal build (prefers 64-bit, falls back
  to 32-bit) and, separately, `playStoreNormal` (same id, Play Store
  channel, cores delivered by Play Feature Delivery — see §4).
- `aarch64` — `applicationIdSuffix '.aarch64'` → **`com.retroarch.aarch64`**,
  arm64-v8a + x86_64 only (`ndk { abiFilters 'arm64-v8a', 'x86_64' }`).
- `ra32` — `applicationIdSuffix '.ra32'` → **`com.retroarch.ra32`**,
  armeabi-v7a + x86 only.

The plugin's `app_status` capability checks all three ids in that order
(first installed wins) via `PluginContext.isAppInstalled`.

buildbot.libretro.com mirrors this: `nightly/android/` (and each
`stable/<version>/android/`) offers `RetroArch.apk` (`normal`),
`RetroArch_aarch64.apk`, `RetroArch_ra32.apk`, all dated
`YYYY-MM-DD-RetroArch[_ABI].apk`.

## 2. Intent launch surface (`RetroActivityFuture`)

RetroArch's own launcher activity
(`pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityFuture.java`,
extending `RetroActivityCamera` → `RetroActivityCommon` → the NDK
`NativeActivity`) and its native counterpart
(`frontend/drivers/platform_unix.c`, `frontend_unix_get_env`) read these
`Intent` extras (all `String`, confirmed by `getStringExtra` call sites):

| Extra | Read by | Effect |
|---|---|---|
| `ROM` | `platform_unix.c:2586` | content path to auto-start (`args->content_path`) |
| `LIBRETRO` | `platform_unix.c:2566` | path to the core `.so` to load (`args->libretro_path`) |
| `CONFIGFILE` | `platform_unix.c:2518` | path to `retroarch.cfg` to use |
| `IME` | `platform_unix.c:2536` | current IME package, restored on exit |
| `DATADIR` | `platform_unix.c:2720` | app-private data dir (`app_dir`, drives §3's app-private paths) |
| `APK` | `platform_unix.c:2653` | path to the installed APK, used as the bundled-assets source |
| `EXTERNAL` | `platform_unix.c:2699` | app-external files dir (`internal_storage_app_path`) |
| `SDCARD` | `platform_unix.c:2610` | shared-storage root (`internal_storage_path`) |
| `AUDIO_RATE` / `AUDIO_FRAMES` | `platform_unix.c:2633,2679` | device-optimal audio defaults |
| `VERSIONCODE` | `platform_unix.c` (bundle-asset section) | gates first-run asset extraction |
| `QUITFOCUS` | `RetroActivityFuture.java` `onCreate` (`getIntent().hasExtra("QUITFOCUS")`) | exit RetroArch when it loses focus |
| `REFRESH` | `RetroActivityFuture.java` `onResume` | preferred display refresh rate |

Every one of these is optional: `platform_unix.c`'s own comment block
(line ~950, "Derivation of launch parameters from the application
context") says a launch with no extras derives the same values by
calling back into the `Activity`/`ApplicationInfo`/`Environment` APIs
directly ("Extras always win; these run only for values still unset
after the intent has been read"). So a plain `launchApp()` and a launch
with `LIBRETRO`/`ROM`/`CONFIGFILE` set both work; the extras are how an
external caller aims a specific core/content/config at a launch that
would otherwise fall back to RetroArch's own last-used state.

`onNewIntent` (`RetroActivityFuture.java`) detects a *different*
`ROM`/`LIBRETRO` pair on an already-running instance, restarts with
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`, and calls
`System.exit(0)` on the old instance — so re-launching with a new core
selection is a normal, supported path, not something that needs the
running instance killed first.

**droidtop's plugin API gap found and closed (local, unpushed — see
§7):** `PluginContext.launchApp(packageName)` (`plugin-host/src/main/kotlin/dev/droidtop/pluginhost/PluginApi.kt`)
only calls `PackageManager.getLaunchIntentForPackage` and starts it with
no way to add extras (`PluginRuntimeService.kt` `launchApp` impl). None
of the extras above can be attached through the existing API. Since
`launchApp`'s own doc comment explains this was deliberate ("without the
plugin holding a Context of its own"), this needed a real, generic
addition rather than a plugin-side workaround.

**Merged 2026-09-27** into `Droidtop/droidtop` main, commit `87fccc4c` (rebased forward to `87fccc4c` after that repo's own history rewrite). `RetroArchPlugin.launchWithExtras` calls `PluginContext.launchAppWithExtras` directly now; the earlier reflection bridge (used while the commit was prepared but unmerged, so this repo's own CI -- which always compiles against droidtop's real, current `:plugin-host` -- stayed green) is gone.

## 3. Where RetroArch's files actually live

Both from `frontend/drivers/platform_unix.c`'s own default-path
derivation (same paths the Java launcher's absent-extras case falls
back to), lines ~2784–2884:

**App-private (`app_dir` = `DATADIR` extra, defaults to
`getApplicationInfo().dataDir`; not writable by another app's UID
without root):**

- `cores` — `DEFAULT_DIR_CORE` (`fill_pathname_join(..., app_dir, "cores", ...)`, `platform_unix.c:2805`)
- `info` — `DEFAULT_DIR_CORE_INFO`, the core `.info` metadata files
- `assets`, `shaders`, `overlays`, `overlays/keyboards`, `autoconfig`,
  `filters/audio`, `filters/video`
- `database/rdb` — `DEFAULT_DIR_DATABASE`

**Shared/external storage (`parent_path`: classic devices get
`SDCARD-extra-root/RetroArch`, i.e. `/storage/emulated/0/RetroArch`;
scoped-storage devices without All Files Access instead get the app's
own `getExternalMediaDirs()[0]`, i.e. under
`/storage/emulated/0/Android/media/<package>/`; both writable by any app
holding the matching storage permission, no root needed):**

- `saves`, `states`, `system` (BIOS/firmware), `screenshots`,
  `downloads` (`DEFAULT_DIR_CORE_ASSETS` — the Online Updater's misc
  content-database/overlay/thumbnail-pack downloads land here), `logs`,
  `config` (+ nested `config/remaps`), `thumbnails`, `playlists`,
  **`cheats`**, `temp`.

This directly contradicts the "assets/database/core-info live in shared
storage" framing in this plugin's own brief: they don't — only
**cheats**, saves/states, system/BIOS, and the Online Updater's misc
downloads folder do. Cores, core-info files and the RDB game database
are app-private, full stop, on every buildbot/F-Droid/sideloaded build.
This plugin's feature set (§5) follows the real split, not the assumed
one.

## 4. Play Store build: cores are Play Feature Delivery, not files

`pkg/android/play-core-impl/com/retroarch/playcore/PlayCoreManager.java`
downloads/deletes cores through Google's
`SplitInstallManager`/`SplitInstallRequest` (Play Feature Delivery) —
`manager.startInstall(...)`/`manager.deferredUninstall(...)`, called
only from inside RetroArch's own process against its own installed
modules. There is no public Android API, intent, or binder surface for
another app to drive this on someone else's behalf; Play delivers each
requested "module" (one per core) as an on-demand APK split tied to
RetroArch's own package. **On a Play Store install, no other app —
droidtop's plugin included — can install or list cores.** The plugin's
core listing/update-check features therefore only apply to a
buildbot/F-Droid/sideloaded RetroArch (the `aarch64`/`ra32`/`normal`
buildbot ids), and the plugin's `app_status` reports which kind is
installed so the UI can say why core management is unavailable on a
Play Store copy.

## 5. RetroArch's own network command interface

`command.c` implements a plaintext UDP command socket
(`command_network_new`, bound with `recvfrom`/`sendto`,
`DEFAULT_NETWORK_CMD_PORT` = **55355**, `command.h:38` /
`config.def.h:1703`), gated by the `network_cmd_enable` setting (off by
default; the user must enable "Network Commands" in RetroArch's own
Network settings and the plugin's own settings row says so explicitly).
Once enabled, the commands relevant here (all confirmed in `command.c`
by their handler function and doc comment):

- **`LOAD_CORE <core path>`** (`command_load_core`) — loads a core
  already present on disk, "mirrors selecting a core in the menu's core
  list". This is the mechanism the plugin uses to make droidtop's chosen
  default core the one RetroArch actually loads next, *without* needing
  to relaunch the activity, when RetroArch is already running.
- **`LOAD_CONTENT <core path>|<content path>`** (`command_load_content`)
  — the network-command equivalent of the `ROM`+`LIBRETRO` intent extras
  for an already-running instance.
- `START_CORE`, `UNLOAD_CORE`, `CLOSE_CONTENT`, plus save/load-state and
  other runtime controls — not this plugin's concern.

**Grepped and confirmed absent:** no `UPDATE_CORE`, no
`CORE_UPDATER`/`ONLINE_UPDATER` command exists anywhere in `command.c`.
The network command interface can select and load a core that is
*already installed*; it cannot trigger a core download, and it cannot
drive the cheats/assets/database updater either. Those still need
either root/Shizuku (write straight into the app-private dirs in §3), or
the user opening RetroArch's own Main Menu → Online Updater by hand —
there is no third path. This plugin is honest about that split in both
its UI copy and this document, per the owner's "accuracy over deference"
standard: it does not pretend a network command exists that doesn't.

The socket is plain UDP with no auth beyond "whoever can reach
`127.0.0.1:55355`" (`command.c`'s own comment at line ~282: "Anyone on
that network can then send LOAD_CORE or ..."), so the plugin only ever
talks to `localhost`, matching RetroArch's own security assumption.

**Not used since 1.3.0.** The plugin runs contained in droidtop (an isolated
process with no sockets; droidtop's docs/plugin-api.md 5.3), and droidtop
offers plugins no UDP, so the `load_core` app_status action that sent
`LOAD_CORE` here is gone. Nothing in droidtop offered it to the person (the
plugin's status never listed it). The facts above stay as the record of
what RetroArch offers.

**In-game commands since 1.4.0, sent by droidtop.** droidtop's
`retroarch.command` (droidtop docs/plugin-api.md 3 B8) sends one of
RetroArch's own hotkey command names (command.h `map[]`: `SAVE_STATE`,
`LOAD_STATE`, `STATE_SLOT_PLUS`, `STATE_SLOT_MINUS`, `FAST_FORWARD`,
`SHADER_TOGGLE`, `SHADER_NEXT`, `SHADER_PREV`, `FPS_TOGGLE` here) to
`127.0.0.1:55355` for the plugin, then `GET_STATUS` (command.c
`command_get_status`: `GET_STATUS PLAYING <system>,<content>`, or
`CONTENTLESS`), so a row can say whether RetroArch answered. RetroArch
has no command that reports the state slot (`command_get_config_param`
knows `video_fullscreen`, the directories, `netplay_nickname`,
`active_replay`, `menu_active` and `cheevos_enable`, not `state_slot`), so
the plugin counts the slot from the changes it made, from RetroArch's
default of 0 for each new content. The rows show only while RetroArch runs
the game (`gamePackages`) and answers.

## 6. buildbot layout (core downloads)

`config.def.h` (`DEFAULT_BUILDBOT_SERVER_URL`, lines 2069–2127) pins one
base URL per platform+ABI; the Android ones are:

```
http://buildbot.libretro.com/nightly/android/latest/armeabi-v7a/
http://buildbot.libretro.com/nightly/android/latest/armeabi/
http://buildbot.libretro.com/nightly/android/latest/arm64-v8a/
http://buildbot.libretro.com/nightly/android/latest/x86/
http://buildbot.libretro.com/nightly/android/latest/x86_64/
```

Confirmed live (fetched 2026-09-27): `nightly/android/latest/` lists
exactly `arm64-v8a/ armeabi/ armeabi-v7a/ x86/ x86_64/`; each ABI folder
lists one zip per core, named `<core>_libretro_android.so.zip` (e.g.
`snes9x_libretro_android.so.zip`, `fbneo_libretro_android.so.zip`).
`stable/<version>/android/` mirrors the same shape for tagged releases.
The plugin downloads over HTTPS
(`https://buildbot.libretro.com/...`, buildbot serves both schemes),
verifies the download is a well-formed zip containing exactly one
`*_libretro_android.so` before treating it as good (buildbot publishes
no separate checksum file per core, so this plugin's own SHA-256 is
recorded only for its own re-download dedup, not upstream verification —
documented as a real limitation, not silently assumed correct), and
extracts it into the plugin's own data, kept by droidtop
(`cores/<abi>/<core>_libretro_android.so` in the `data` API; since 1.3.0
the download itself is droidtop's `net.download`, limited to the declared
buildbot.libretro.com, and the zip and the core are read and written
through descriptors droidtop hands over)
because that is the only place this plugin can write without root (§3).

## 7. What the plugin does with root/Shizuku vs. without (owner directive: root optional, core function must work without it)

**Without root (core function, always available):**

- `app_status`: which RetroArch package (if any) is installed, its
  `versionName`, and whether it is the Play Store build (no reliable
  static signal for that from `PackageManager` alone, so the plugin
  reports "unknown, Play Store cores are unmanageable either way" unless
  the installed id has no buildbot-style debug/nightly marker it can
  read from `applicationInfo.metaData` — in practice the plugin treats
  `com.retroarch` as ambiguous and `com.retroarch.aarch64`/`.ra32` as
  known-buildbot, and says so in the row instead of guessing further).
- `status_tile`: "RetroArch: <installed|not installed>, core X <ready|missing>".
- Download a core from buildbot into the plugin's own private dir (§6),
  as a `startJob` with progress (download% then "verifying").
- Launch RetroArch via the extended `launchAppWithExtras` (§2) with
  `LIBRETRO` pointed at that private-dir core path. **This is expected
  to fail** on a stock RetroArch install: RetroArch's own core loader
  (`libretro_core.c` core-load path, not modified here) `dlopen()`s the
  path verbatim, and Android's per-app data-directory isolation means a
  path under droidtop's private dir is not even readable by RetroArch's
  UID, let alone executable — this is the same reason RetroArch itself
  keeps cores under its *own* app-private dir rather than shared
  storage. The plugin still offers this path for the rare case
  (rooted device, or a RetroArch build with relaxed SELinux) where it
  works, but its settings row states the expected outcome plainly rather
  than promising a working "download and play" flow it cannot deliver
  non-root. **The always-correct non-root fallback** the settings row
  leads with is: open RetroArch's own Main Menu → Online Updater →
  Core Downloader (via `PluginContext.launchApp`) with the exact core
  name shown, since that is a real, working, user-driven path on every
  install.
- `LOAD_CORE`/`LOAD_CONTENT` over the network command port (§5) would
  work non-root for a core RetroArch already has, but a contained plugin
  has no sockets (§5, "Not used since 1.3.0").
- Update **cheats** by downloading buildbot's `cheats.zip` bundle
  (`buildbot.libretro.com/assets/frontend/cheats.zip`, the same archive
  RetroArch's own Online Updater fetches for "Update Cheats") and
  extracting it into the shared-storage `cheats` folder (§3) — genuinely
  writable non-root via `MediaStore`/`SAF`-free direct file I/O on a
  device where droidtop already holds broad storage access (matching
  how droidtop's own JSON `acquire_content` writes into a library
  folder, §12).

**With a root helper plugin the user allowed (contract 2, 1.1.0), enhancement
only.** The plugin never runs `su` and never asks droidtop for a root tick
(`requestsRoot` is false). It declares an optional `requires` on `priv.shell`
at root level and the permission `priv.shell.root`, and calls the helper
through droidtop's broker (`host.call("priv.shell", "exec", {argv})`,
droidtop `docs/plugin-api.md` 2.7), which runs each command directly, without
a shell, as root. That is the only way the plugin reaches RetroArch's private
folder, and every step below is one such command:

- Put a downloaded core into RetroArch's real `cores` dir
  (`/data/data/<pkg>/cores/`): `mkdir -p`, `cp`, then `stat -c %u:%g` on
  `/data/data/<pkg>` and `chown` of the copy to that owner, `chmod 755` and
  `restorecon`. The chown is the part the earlier `su -c cp` left out: a file
  root copied is root's, and RetroArch's own user could not read it, so
  `LIBRETRO` and RetroArch's core list could not load it. Not yet run on a
  device; a rig check covers it (see the 1.1.0 changelog entry).
- List what RetroArch's cores folder holds (`ls`), when the user presses
  "Check what RetroArch has" on the panel, so the panel can say which cores
  are in RetroArch, instead of only the ones this plugin downloaded.
- Pushing updated `database/rdb` and `info` files from buildbot `.zip`s
  (`assets/frontend/info.zip`, `assets/frontend/database-rdb.zip`) is the same
  helper path; this plugin does not do it yet.

The Quick Menu panel (droidtop `ui.panel`, drawn by droidtop from the view the
plugin returns) is the plugin's control point: RetroArch's status and an Open
button; one row per system the user chose RetroArch for with its core and
whether the core is in RetroArch, only downloaded, or missing, and a press that
downloads (and with the helper installs) it; a "Core name" field with
"Download and install"; and the two settings. With the optional `library.read`
permission the systems on the panel are droidtop's own list (`library.read`
`systems`, droidtop docs/plugin-api.md A1: every system with games and the
emulator and core a launch would use), filtered to the ones whose player is a
RetroArch package. Without it, or on a droidtop that predates the call, they
are the ones droidtop has reported through `library.default_player_changed`
since the plugin was installed; a system whose player moves away from
RetroArch leaves that list.

Every root-only action above has the non-root fallback stated next to it
in the settings row copy, per the owner's "core function must keep
working without root" rule — the plugin never blocks on root, it only
does less.

## 8. Licence

This plugin is original Kotlin against droidtop's own `plugin-host` API;
it contains **no RetroArch source**, links against no RetroArch code,
and downloads only RetroArch's own published binary artifacts (cores,
cheats/database/info archives) at runtime, the same way RetroArch's own
Online Updater does — it does not redistribute them. RetroArch is
GPL-3.0 (`github.com/libretro/RetroArch`, `LICENSE`), but that only
constrains code derived from or linked against it, neither of which
applies here. droidtop's plugin ecosystem otherwise uses whatever
licence the reused upstream requires (owner directive, "most open
compatible licence"); since nothing is reused from RetroArch, this
repository uses the **MIT licence** — the most permissive option and the
same choice as droidtop's other from-scratch plugin samples.
