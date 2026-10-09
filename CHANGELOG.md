# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.3.0] - 2026-10-09

### Changed
- Runs contained in droidtop (droidtop docs/plugin-api.md 5.3): an isolated process with no network and no files of its own. Cores are downloaded by droidtop (`net.download`, still only from buildbot.libretro.com, as declared) into the plugin's own data, and read and written there through file descriptors droidtop hands over; the root helper gets droidtop's path of the core (`data.path`) to copy it into RetroArch. The settings, the systems heard about and the last look into RetroArch's folder are kept in the same data.
- Needs a droidtop with the contained tier (`data`, `net.download`, `plugins.job_status` with `percent`, `plugins.job_cancel`, `data.move`, `data.path`, `PluginContext.openFile`).
- CI now signs the plugin bundle itself (repo secret `PLUGIN_SIGNING_KEY`, optional `PLUGIN_SIGNING_CERT` packaged as `origin.cert`) and attaches it to the release for a `plugin-v*` tag.

### Removed
- The `load_core` app_status action, which sent `LOAD_CORE` to RetroArch's UDP command port: a contained plugin has no sockets, and droidtop never offered the action to anyone.

### Note
- Settings, systems and downloaded cores kept by 1.2.0 in the old data folder are the same folder droidtop's `data` API serves, so they carry over.

### Needs a rig check
- Approve the update, open the panel, press a system row with a missing core: the core downloads (progress shown, Jobs lists "Download cores/..." by droidtop), and with a root helper allowed it lands in RetroArch. Cancel one download from Jobs: it stops. Switch the two settings, leave and come back: they stayed.

## [1.2.0] - 2026-10-07

### Added
- The panel lists every system you have games for that RetroArch launches, with the core each one needs. It asks droidtop for the list (`library.read`: the system names, the emulator and core chosen for each, never your games), where before it only knew the systems you had changed the player of since installing it.

### Changed
- New access, shown on the approval list when you update: `library.read` ("See your library: games, apps and systems"). It is optional. Without it the panel keeps working from the systems droidtop tells it about when you choose a player, and says that allowing it lists them all.
- Needs a droidtop that answers `library.read` `systems`; on an older one the panel falls back the same way.

### Needs a rig check
- Allow "See your library" for the plugin, open its panel and confirm every system that has games and uses RetroArch is listed, including one whose player you never changed. Turn the permission off and confirm the panel still opens with the shorter list.

## [1.1.0] - 2026-10-07

### Added
- A Quick Menu panel (droidtop contract 2 `ui.panel`): RetroArch's status with an Open button, a row for each system you chose RetroArch for showing its core and whether that core is in RetroArch, only downloaded or missing, a press on a row to download and install it, a "Core name" field with Download and install, and the plugin's settings.
- "Check what RetroArch has", which lists RetroArch's own cores folder through a root helper plugin you allowed, so the panel can tell cores that are in RetroArch from ones that are only downloaded.
- Two switches: download a system's core when you choose RetroArch for it, and put automatically downloaded cores into RetroArch. A core you ask for yourself is always installed when a helper is allowed.
- A settings page under droidtop's Settings with the same switches.

### Changed
- Contract 2 manifest. New access, shown on the approval list when you update: finding which RetroArch you have (`apps.check`, its three package ids), opening it (`apps.launch`, `apps.intents.out`, the same ids), downloading cores (`net.domains`, buildbot.libretro.com only) and, optionally, running commands as root through a helper plugin (`priv.shell.root`). Nothing else is asked for. The plugin no longer asks droidtop for a root tick (`requestsRoot` is false).
- Getting a core into RetroArch's folder goes through the root helper plugin and droidtop's broker instead of the plugin running `su` itself. The copy now also gives the file to RetroArch's own user and makes it executable, which the old copy did not.
- Core names are checked (letters, digits, `-` and `_`) before they are used in an address, a file name or a command.
- The status rows report whether a root helper can install cores (`elevatedInstall`) in place of `rootApproved` and `shizukuAvailable`.

### Removed
- The flat settings rows of contract 1; the settings page replaces them.

### Needs a rig check
- With a root helper plugin installed and allowed: press a core row on the panel and confirm the core appears in RetroArch (Main Menu, Load Core) and loads. If it does not load, the owner or SELinux label of the copied file is the thing to look at.

## [1.0.0] - 2026-09-27

### Added
- RetroArch manager plugin that detects an installed RetroArch and launches it with a chosen core, content, and config file through RetroArch's own documented Intent extras.
- Core downloads by name from the libretro buildbot with live progress; with root approved a downloaded core is copied straight into RetroArch's own cores directory, and without root the user is pointed at RetroArch's own Core Downloader.
- Automatic download of a system's configured core when RetroArch is made the default player for it and the core is not downloaded yet.
- Cancelling a core download while it is in flight.
- Loading a core in an already-running RetroArch over its network command socket when the user has Network Commands enabled.
- Status and settings rows showing whether RetroArch is installed (and which package), whether root is in use, and which cores the plugin has downloaded.
- Design documentation citing every claim the plugin relies on against RetroArch's own sources.

### Changed
- Launching calls the host app's launch-with-extras API directly now that it is part of the plugin host API; the compatibility fallback is gone.
- CI builds accept the vendored SDL2 wrapper checksum, the same known-good one the host app's own build setup allows.

### Fixed
- Concurrent core downloads no longer interfere with each other: cancelling one download no longer silently cancels every other download in flight, and two downloads of the same core no longer fail when one job deletes the shared zip; each job now stages under its own name and only moves the core into place once its own download and extract fully succeed.
- Building against the plugin host API as it really exists: the job start call's signature was corrected and the missing platform jar was added to the compiler classpath.
- Build output and the signed bundle, committed by mistake, were removed from the repository and are now ignored.
