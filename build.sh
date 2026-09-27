#!/usr/bin/env bash
# Compiles, dexes and hashes droidtop.retroarch's payload from this
# repo's src/, in the shape PluginBundleInstaller.install() expects
# (droidtop docs/SPEC.md 12a) -- the same pattern droidtop's own
# samples/plugin-sample-statustile/build.sh documents, copied here since
# a real plugin author's repo is the intended shape it demonstrates.
#
# This plugin has no native .so payload of its own (it only talks to an
# ALREADY INSTALLED RetroArch over Intents/UDP/HTTP), so there is one
# dex output and no per-ABI lib/ directory, same as the sample.
#
# Split from signing (see sign.sh) on purpose: this script never touches
# the plugin origin's private key and is safe to run in CI (see
# .github/workflows/build.yml), which uploads build/manifest.json +
# build/classes.jar UNSIGNED. Only droidtop-dev, which holds
# /root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem,
# ever runs sign.sh.
#
# Prerequisites:
#   - kotlinc on PATH (matching droidtop's own gradle/libs.versions.toml
#     "kotlin" entry)
#   - d8 on PATH (Android SDK build-tools)
#   - a compiled :plugin-host classes jar to compile against
#     (PLUGIN_HOST_CLASSPATH)
#   - ANDROID_JAR pointing at android.jar for :plugin-host's compileSdk
#
# Optionally, for a one-shot local build+sign (droidtop-dev only): also
# set PLUGIN_SIGNING_KEY and this script calls sign.sh itself at the end.

set -euo pipefail
cd "$(dirname "$0")"

: "${PLUGIN_HOST_CLASSPATH:?set to a jar/dir containing dev.droidtop.pluginhost.* compiled classes}"
: "${ANDROID_JAR:?set ANDROID_JAR to android.jar for the target compileSdk}"

rm -rf build
mkdir -p build/classes

kotlinc -cp "$PLUGIN_HOST_CLASSPATH:$ANDROID_JAR" -d build/classes src/dev/droidtop/plugins/retroarch/RetroArchPlugin.kt

d8 --output build --lib "$ANDROID_JAR" \
  $(find build/classes -name '*.class')

# classes.jar is a zip containing classes.dex at its root -- what
# DexClassLoader (PluginRuntimeService) expects.
(cd build && zip -q classes.jar classes.dex)

CLASSES_SHA=$(sha256sum build/classes.jar | cut -d' ' -f1)

python3 - "$CLASSES_SHA" << 'PY'
import json, sys
sha = sys.argv[1]
manifest = json.load(open("manifest.template.json"))
manifest["payload"] = [{"path": "classes.jar", "sha256": sha}]
json.dump(manifest, open("build/manifest.json", "w"), indent=2, sort_keys=True)
PY

echo "Built build/classes.jar and build/manifest.json (unsigned)"
sha256sum build/classes.jar

if [ -n "${PLUGIN_SIGNING_KEY:-}" ]; then
  PLUGIN_SIGNING_KEY="$PLUGIN_SIGNING_KEY" ./sign.sh
else
  echo "PLUGIN_SIGNING_KEY not set -- stopping here, unsigned."
  echo "Run ./sign.sh with PLUGIN_SIGNING_KEY set (droidtop-dev only; the key never leaves that host) to produce droidtop.retroarch.droidplugin.tar.xz."
fi
