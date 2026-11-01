#!/bin/bash
# Builds ES-DE Art Grabber without Gradle: aapt + javac + dx + zipalign + apksigner.
set -euo pipefail
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/gen build/obj build/apk
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK" \
      -d build/obj $(find src build/gen -name '*.java')
dalvik-exchange --dex --output=build/apk/classes.dex build/obj
aapt package -f -M AndroidManifest.xml -S res -I "$SDK" -F build/unsigned.apk
(cd build/apk && aapt add ../unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
[ -f release.keystore ] || keytool -genkeypair -keystore release.keystore -alias artgrabber \
      -keyalg RSA -keysize 2048 -validity 10000 -storepass artgrabber -keypass artgrabber \
      -dname "CN=ES-DE Art Grabber" >/dev/null 2>&1
apksigner sign --ks release.keystore --ks-key-alias artgrabber --ks-pass pass:artgrabber \
      --key-pass pass:artgrabber --out ES-DE-Art-Grabber.apk build/aligned.apk
apksigner verify --print-certs ES-DE-Art-Grabber.apk | head -2
ls -la ES-DE-Art-Grabber.apk
