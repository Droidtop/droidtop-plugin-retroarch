# droidtop-plugin-retroarch

A droidtop (`native_bundle`) plugin that integrates an **already
installed** RetroArch: detects it, launches it with a specific
core/ROM/config through RetroArch's own documented Intent extras,
downloads cores from `buildbot.libretro.com`, and can send it live
commands over its own UDP command socket when the user has that turned
on. See [`DESIGN.md`](DESIGN.md) for every fact this plugin relies on,
each cited against RetroArch's own source.

This plugin never bundles, patches, or redistributes any RetroArch code;
it only talks to a copy the user installed themselves, the same way any
other app on the device could.

## Layout

- `manifest.template.json` — the plugin manifest (`droidtop.retroarch`,
  `native_bundle`, capabilities `app_status`/`status_tile`/`settings_rows`,
  `requestsRoot: true` as an optional enhancement — see DESIGN.md 7).
- `src/dev/droidtop/plugins/retroarch/RetroArchPlugin.kt` — the plugin.
- `build.sh` — compiles+dexes the payload against droidtop's
  `:plugin-host` API (unsigned; this is what CI runs).
- `sign.sh` — signs the built payload into a
  `droidtop.retroarch.droidplugin.tar.xz` installable bundle. Needs the
  droidtop plugin origin's private key; never run in CI.

## Building

```
export PLUGIN_HOST_CLASSPATH=/path/to/plugin-host-debug-classes.jar
export ANDROID_JAR=$ANDROID_SDK_ROOT/platforms/android-36/android.jar
./build.sh
```

`PLUGIN_HOST_CLASSPATH` needs a compiled `dev.droidtop.pluginhost.*`
classpath from a droidtop checkout (`./gradlew :plugin-host:assembleDebug`,
then unzip `classes.jar` out of the resulting AAR) — see
`.github/workflows/build.yml` for exactly how CI does this against
`Droidtop/droidtop`'s own `main`.

## Root

Root is optional and only ever an enhancement (droidtop's own standing
rule): every feature here has a working non-root path, documented next
to its root-only counterpart in `DESIGN.md` §7. The plugin never
requires the user to grant root.
