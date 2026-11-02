#!/bin/sh
set -e
T=/Users/damianbadita/Desktop/tv-apps/build-tools
export JAVA_HOME=$T/jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
BT=$(ls -d $T/bt/*/ | head -1)
AJ=$T/pf/android-34/android.jar
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$AJ" --manifest AndroidManifest.xml -A assets build/res.zip -o build/base.apk --min-sdk-version 26 --target-sdk-version 34
javac -nowarn --release 8 -cp "$AJ" -d build/classes src/com/damian/tvhome/*.java 2>&1 | grep -v "^Note:\|warning" || true
"$BT/d8" --release --lib "$AJ" --output build $(find build/classes -name '*.class')
(cd build && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
KS=$T/dev.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias dev -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out build/tvhome.apk build/aligned.apk
ls -la build/tvhome.apk
