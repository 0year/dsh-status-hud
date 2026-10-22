#!/bin/bash
# 手动打 APK（无需 Gradle）：aidl + javac + d8 + aapt + zipalign + apksigner
# 依赖：先运行 ../scripts/fetch-deps.sh；系统需安装 aidl aapt apksigner zipalign zip unzip（Android SDK build-tools）
set -e
cd "$(dirname "$0")"
BUILD=build
SDK_JAR=${SDK_JAR:-$BUILD/android.jar}
D8_JAR=${D8_JAR:-$BUILD/d8.jar}
SHZ_API=${SHZ_API:-$BUILD/shizuku-api.jar}
SHZ_PROVIDER=${SHZ_PROVIDER:-$BUILD/shizuku-provider.jar}
SHZ_AIDL=${SHZ_AIDL:-$BUILD/shizuku-aidl.jar}
SHZ_SHARED=${SHZ_SHARED:-$BUILD/shizuku-shared.jar}
OUT=${OUT:-../dist/DSH-Status-Hud.apk}
GEN=$BUILD/gen

[ -f "$SDK_JAR" ] || { echo "缺 $SDK_JAR，先运行 ../scripts/fetch-deps.sh"; exit 1; }
[ -f "$D8_JAR" ]  || { echo "缺 $D8_JAR，先运行 ../scripts/fetch-deps.sh"; exit 1; }
[ -f "$SHZ_API" ] || { echo "缺 $SHZ_API，先运行 ../scripts/fetch-deps.sh"; exit 1; }

rm -rf "$BUILD/classes" "$BUILD/dex" "$GEN"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$GEN"

echo "== 1/7 aidl =="
aidl --lang=java -Isrc -o "$GEN" src/com/dshstatus/hud/IUserService.aidl

echo "== 2/7 javac =="
javac -source 8 -target 8 -Xlint:-options \
      -bootclasspath "$SDK_JAR" \
      -classpath "$SHZ_API:$SHZ_PROVIDER:$SHZ_AIDL:$SHZ_SHARED" \
      -d "$BUILD/classes" \
      $(find src -name '*.java') $(find "$GEN" -name '*.java')

echo "== 3/7 d8 =="
java -cp "$D8_JAR" com.android.tools.r8.D8 --lib "$SDK_JAR" --min-api 29 \
     --output "$BUILD/dex" \
     $(find "$BUILD/classes" -name '*.class') "$SHZ_API" "$SHZ_PROVIDER" "$SHZ_AIDL" "$SHZ_SHARED"

echo "== 4/7 aapt package =="
aapt package -f -M AndroidManifest.xml -I "$SDK_JAR" -F "$BUILD/base.apk"
zip -j -q "$BUILD/base.apk" "$BUILD/dex/classes.dex"
unzip -l "$BUILD/base.apk" | tail -4

echo "== 5/7 zipalign =="
zipalign -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "== 6/7 keystore =="
if [ ! -f "$BUILD/debug.keystore" ]; then
  keytool -genkeypair -keystore "$BUILD/debug.keystore" -alias dsh -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=DSH Status HUD, OU=dev, O=dsh, L=CN, ST=CN, C=CN"
fi

echo "== 7/7 apksigner =="
mkdir -p "$(dirname "$OUT")"
apksigner sign --ks "$BUILD/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$BUILD/aligned.apk"
apksigner verify --print-certs "$OUT" | head -2
ls -la "$OUT"
