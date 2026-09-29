# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
