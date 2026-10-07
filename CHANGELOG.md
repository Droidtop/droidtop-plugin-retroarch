# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
