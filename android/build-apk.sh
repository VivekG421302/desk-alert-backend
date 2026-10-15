#!/usr/bin/env bash
# Command-line build WITHOUT Gradle (Ubuntu: apt install aapt apksigner zipalign dalvik-exchange libandroid-23-java openjdk-17-jdk-headless)
# Usage: KS_PASS=yourpassword ./build-apk.sh   -> DeskAlert.apk
set -euo pipefail
: "${KS_PASS:?Set KS_PASS to a keystore password}"
cd "$(dirname "$0")"
AJ=/usr/share/java/com.android.android-23.jar
DX=/usr/share/java/com.android.dx.jar
KS="${KS:-$HOME/.deskalert.jks}"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/gen" "$W/obj"

# Gradle-style manifest -> add package/sdk attributes for aapt
sed -e 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.deskalert.trigger" android:versionCode="7" android:versionName="2.4"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>#' \
  app/src/main/AndroidManifest.xml > "$W/AndroidManifest.xml"
grep -q 'package="com.deskalert.trigger"' "$W/AndroidManifest.xml" || { echo "manifest rewrite failed"; exit 1; }

aapt package -f -m -J "$W/gen" -M "$W/AndroidManifest.xml" -S app/src/main/res -I "$AJ" -F "$W/res.apk"
javac --release 8 -cp "$AJ" -d "$W/obj" "$W"/gen/com/deskalert/trigger/R.java app/src/main/java/com/deskalert/trigger/*.java
java -cp "$DX" com.android.dx.command.Main --dex --min-sdk-version=24 --output="$W/classes.dex" "$W/obj"
cp "$W/res.apk" "$W/unsigned.apk"
(cd "$W" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$W/unsigned.apk" "$W/aligned.apk"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias deskalert -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=Desk Alert"
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out DeskAlert.apk "$W/aligned.apk"
apksigner verify DeskAlert.apk && echo "Built DeskAlert.apk"
