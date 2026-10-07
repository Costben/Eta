#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
VD_SDK="${ANDROID_SDK_ROOT:-/Users/rinshibuya/Library/Android/sdk}"
VD_TOOLS="$VD_SDK/build-tools/37.0.0"
VD_PLATFORM="$VD_SDK/platforms/android-37.0/android.jar"
VD_JAVA="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}/bin"
mkdir -p build/classes build/dex artifacts
"$VD_JAVA/javac" -source 8 -target 8 -encoding UTF-8 -classpath "$VD_PLATFORM" -d build/classes src/io/github/costben/etavdprobe/*.java
"$VD_JAVA/jar" cf build/classes.jar -C build/classes .
"$VD_TOOLS/d8" --min-api 33 --lib "$VD_PLATFORM" --output build/dex build/classes.jar
"$VD_JAVA/jar" cf build/probe.jar -C build/dex classes.dex
"$VD_TOOLS/aapt2" link -I "$VD_PLATFORM" --manifest AndroidManifest.xml -o build/probe-unsigned.apk
cp build/dex/classes.dex build/classes.dex
(cd build && zip -q probe-unsigned.apk classes.dex)
"$VD_TOOLS/zipalign" -f 4 build/probe-unsigned.apk build/probe-aligned.apk
if [ ! -f build/probe.keystore ]; then
    "$VD_JAVA/keytool" -genkeypair -keystore build/probe.keystore -storepass android -keypass android -alias probe -keyalg RSA -keysize 2048 -validity 365 -dname CN=EtaVDProbe
fi
"$VD_TOOLS/apksigner" sign --ks build/probe.keystore --ks-key-alias probe --ks-pass pass:android --key-pass pass:android --out build/probe.apk build/probe-aligned.apk
"$VD_TOOLS/apksigner" verify build/probe.apk
printf 'BUILD_PASS build/probe.jar build/probe.apk\n'
